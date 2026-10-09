//! The camera-space side of the trunk: where the demosaic result stops being "camera" data, and
//! the halves that meet there.
//!
//! The render trunk has **three** stages, and this module owns the boundary between the first two:
//!
//! 1. **develop** (`crate::develop::develop_to_camera_image`) — decode → scaling → deprofile →
//!    exposure → denoise → dehaze → CA → LoCA → demosaic → white balance. Everything here depends
//!    only on [`DevelopParams`](crate::develop::DevelopParams) and the decoded file, which is
//!    exactly what makes its product cacheable.
//! 2. **oklab** — the camera-space highlight roll-off, which used to be buried inside the colour
//!    mapping at `calibrate`. It is cheap (a per-pixel round trip that returns most pixels
//!    untouched) and, crucially, it sits **above** the cache: the same demosaiced buffer serves both
//!    output spaces and either roll-off setting, so it is applied on the way out rather than baked
//!    in. See [`DemosaicedCameraImage::to_working_space`].
//! 3. **grade / output** — crop → camera→working matrix → gamut clip, and then (when `grade` is on)
//!    the rawalchemy hand-off.
//!
//! [`DemosaicedCameraImage`] is the artefact of stage 1: a camera-space RGB buffer plus every
//! non-pixel fact the later stages need (the D65 camera matrix the OKLab stage round-trips
//! through, the two camera→working matrices, the crop rectangle already re-based into this
//! buffer's coordinates). Nothing downstream needs the `RawImage`, which is what lets the resident
//! object drop its decoded pixels and lets a render whose later stages changed skip stage 1.
//!
//! # Why the later stages never mutate the cache
//!
//! Kotlin owns the cache handle and hands the *same* object back on every subsequent render, so
//! stage 1's buffer has to survive being read any number of times. Every stage after it therefore
//! reads through [`DemosaicedCameraImage`] and writes into a **freshly allocated** output buffer
//! rather than transforming in place. That costs exactly one buffer of the output size per render —
//! which is also the minimum, since the result has to be built somewhere — and it is why there is
//! no fast path that hands the cache's own allocation to the encoder.
//!
//! Ordering note: crop, OKLab and the camera→working multiply are all per-pixel (crop is a
//! rectangle), so they commute. They are applied in that order because it is the cheapest one: crop
//! first means the OKLab round trip and the matrix multiply both touch only the pixels that
//! survive, and OKLab — the only one of the three that cannot be folded into the output write —
//! touches a buffer as small as the crop allows.

use rayon::prelude::*;

use rawler::imgop::develop::Intermediate;
use rawler::imgop::xyz::Illuminant;
use rawler::RawImage;

use crate::calibrate::{resolve_xyz_to_cam, WorkingSpace};
use crate::calibrate_oklab::OklabBypassMaps;
use crate::develop::RawlerImageDeveloped;
use crate::RawlerFotlabError;

/// How many components the demosaiced camera-space buffer carries.
///
/// This is not a detail: it decides both the buffer stride and whether a colour matrix applies at
/// all, and the two answers do not line up with each other. A monochrome CFA is *stored* as three
/// components (the only per-pixel operation it admits is replicating the single channel) but is
/// *exempt* from the matrix — running a colour matrix over three identical values would invent a
/// colour cast out of a grey image. A four-colour CFA keeps all four, because each output row of the
/// matrix genuinely has a fourth coefficient to consume.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum CameraChannels {
  /// One photosite per pixel. Stored as 3 identical components, no white balance, no matrix.
  Monochrome,
  /// Ordinary 3-colour CFA.
  Three,
  /// 4-colour CFA (RGB + a fourth); each output row has a fourth coefficient.
  Four,
}

impl CameraChannels {
  /// Components per pixel in the stored buffer.
  fn components(self) -> usize {
    match self {
      CameraChannels::Monochrome | CameraChannels::Three => 3,
      CameraChannels::Four => 4,
    }
  }
}

/// The OKLab stage's per-output switches.
///
/// The stage has a master gate ([`PipelineStages::oklab`](crate::develop::PipelineStages::oklab)) —
/// "run the stage at all" — and one sub-switch per *output*, so a render can roll off the sRGB
/// presentation highlights and leave the ProPhoto editing buffer alone, or the other way round.
/// Which sub-switch applies is decided by the output space, which is itself derived from the same
/// dictionary (`grade` on → ProPhoto D50), so the caller never has to name a space twice.
///
/// `false` for every sub-switch makes the stage an identity; Kotlin uses exactly that to derive the
/// master gate.
#[derive(Debug, Clone, Copy)]
pub(crate) struct OklabSwitches {
  /// Sub-switch: highlight-chroma compression for the `SrgbD65` presentation output.
  pub highlight_compress_srgb: bool,
  /// Sub-switch: highlight-chroma compression for the `ProPhotoD50` graded output.
  pub highlight_compress_prophoto: bool,
}

impl OklabSwitches {
  /// Whether the roll-off runs for a render landing in [space].
  pub fn enabled_for(self, space: WorkingSpace) -> bool {
    match space {
      WorkingSpace::SrgbD65 => self.highlight_compress_srgb,
      WorkingSpace::ProPhotoD50 => self.highlight_compress_prophoto,
    }
  }
}

/// The **develop stage's** product: a demosaiced, white-balanced camera-space image that every
/// later stage can be driven from without the `RawImage`.
///
/// Handed to Kotlin as a UniFFI handle after each render that ran stage 1
/// (`RawlerImageLoaded::take_demosaiced_camera_image`) and passed back in on the next render, so a
/// grade-only or OKLab-only change costs one matrix multiply instead of a decode. See
/// [`crate::develop::PipelineStages`] for the rule governing when that is legal.
#[derive(uniffi::Object)]
pub struct DemosaicedCameraImage {
  width: u32,
  height: u32,
  channels: CameraChannels,
  /// Row-major camera-space components, `width * height * channels.components()` long, white
  /// balance already applied. The OKLab stage has **not** run: this is pre-roll-off by design.
  rgb: Vec<f32>,
  /// XYZ→camera at D65. The OKLab stage anchors both of its maps on XYZ(D65) regardless of the
  /// output space, so this is the one matrix it needs — and it is why the roll-off can run above
  /// the cache without the cache having to know which output is coming.
  xyz2cam: [[f32; 3]; 4],
  cam2rgb_srgb: [[f32; 4]; 3],
  cam2rgb_prophoto: [[f32; 4]; 3],
  /// Crop rectangle already re-based from full-sensor into this buffer's coordinates (including
  /// the decimation a quarter-resolution demosaic implies).
  crop_x: u32,
  crop_y: u32,
  crop_w: u32,
  crop_h: u32,
}

#[uniffi::export]
impl DemosaicedCameraImage {
  /// Width in pixels of the demosaiced buffer.
  pub fn width(&self) -> u32 {
    self.width
  }

  /// Height in pixels of the demosaiced buffer.
  pub fn height(&self) -> u32 {
    self.height
  }
}

impl DemosaicedCameraImage {
  /// Run the two stages that sit above the cache boundary and produce the finished linear image.
  ///
  /// * the **OKLab stage** — `oklab_enabled` is the master gate from the render dictionary, `oklab`
  ///   the per-output sub-switches. Skipped outright when either says no, and for a monochrome or
  ///   four-colour buffer (the round trip is defined on camera *triples*, which is what those two
  ///   do not have).
  /// * the **output stage** — crop to the recommended area, project camera→working space with the
  ///   matrix [space] selects, then clamp into gamut when asked (the editing path's own business;
  ///   the presentation PNG is finished by `bound`, which clips after the transfer function).
  ///
  /// `space` is the only place the two output primaries are distinguished, and it comes from
  /// [`PipelineStages::working_space`](crate::develop::PipelineStages::working_space).
  pub(crate) fn to_working_space(
    &self,
    space: WorkingSpace,
    oklab_enabled: bool,
    oklab: OklabSwitches,
    clip_to_gamut: bool,
  ) -> Result<RawlerImageDeveloped, RawlerFotlabError> {
    let (cw, ch) = (self.crop_w, self.crop_h);
    // A zero-area rectangle yields an empty image, which is what the pre-split pipeline produced
    // for the same input (rawler's `CropDefault` can legitimately return nothing).
    if cw == 0 || ch == 0 {
      return Ok(RawlerImageDeveloped { width: cw, height: ch, rgb: Vec::new() });
    }
    self.check_crop_fits()?;

    // The OKLab stage. Materialising the cropped camera triples is only worth it when the stage
    // actually runs — it is the one step that cannot be folded into the output write, because the
    // round trip is per-pixel *in camera space*, before the matrix has had its say.
    let rolled = if oklab_enabled && oklab.enabled_for(space) && self.channels == CameraChannels::Three
    {
      let mut buf = self.read_cropped();
      OklabBypassMaps::new(&self.xyz2cam).compress_buffer_flat(&mut buf);
      Some(buf)
    } else {
      None
    };

    let mut rgb = self.project(rolled.as_deref(), space);

    // Out-of-gamut clipping (the Studio "Clipping" switch) — the LAST step, so every consumer of
    // the editing path sees an in-gamut buffer: rawalchemy's grade, the auto-exposure meter, and
    // any handle handed back to Kotlin.
    //
    // NaN passes through unchanged (`f32::clamp` returns a NaN input); a well-formed develop
    // contains none, and substituting a value here would only hide the loader failure that
    // produced it. This is a plain per-channel **clip**, not gamut mapping.
    if clip_to_gamut {
      rgb.par_iter_mut().for_each(|v| *v = v.clamp(0.0, 1.0));
    }
    Ok(RawlerImageDeveloped { width: cw, height: ch, rgb })
  }

  /// The crop rectangle has to sit inside the buffer it is about to read.
  ///
  /// `crop_rect` already rejected a rectangle that does not fit when the cache was built, so this
  /// cannot fire for an object that came from [`to_camera_space`] — it is here because everything
  /// below indexes rather than iterates, and a slice that runs off the end is a panic, which this
  /// crate is not allowed to produce (`FOTLAB-CRASH-000001`).
  fn check_crop_fits(&self) -> Result<(), RawlerFotlabError> {
    let (buf_w, buf_h) = (self.width as usize, self.height as usize);
    let (x, y) = (self.crop_x as usize, self.crop_y as usize);
    if x + self.crop_w as usize > buf_w || y + self.crop_h as usize > buf_h {
      return Err(RawlerFotlabError::Decode(format!(
        "crop rect {}x{}+{}+{} does not fit the {}x{} demosaiced buffer",
        self.crop_w, self.crop_h, x, y, buf_w, buf_h
      )));
    }
    Ok(())
  }

  /// Copy the cropped camera-space window out into a contiguous buffer.
  ///
  /// Row-wise, so each row is an independent contiguous memcpy — parallelised with rayon instead of
  /// being walked sequentially (`OPTIMZ-PERFRM-000007`). Only called when the OKLab stage runs, and
  /// only for a three-colour buffer, so `stride` is 3 and the result is a packed triple buffer.
  fn read_cropped(&self) -> Vec<f32> {
    let stride = self.channels.components();
    let (cw, ch) = (self.crop_w as usize, self.crop_h as usize);
    let buf_w = self.width as usize;
    let (x, y) = (self.crop_x as usize, self.crop_y as usize);
    let row_len = cw * stride;
    let mut out: Vec<f32> = vec![0f32; row_len * ch];
    out.par_chunks_mut(row_len).enumerate().for_each(|(row, dst)| {
      let start = ((y + row) * buf_w + x) * stride;
      dst.copy_from_slice(&self.rgb[start..start + row_len]);
    });
    out
  }

  /// Crop + project camera space into [space], in a single pass, into a fresh buffer.
  ///
  /// [cropped] is the materialised crop [`Self::read_cropped`] produced for the OKLab stage, or
  /// `None` to read straight through the crop window. Both index into the same slice — the whole
  /// cached buffer in the second case — and differ only in the stride between successive pixels,
  /// so there is one per-pixel body rather than two copies of it.
  fn project(&self, cropped: Option<&[f32]>, space: WorkingSpace) -> Vec<f32> {
    let stride = self.channels.components();
    let (cw, ch) = (self.crop_w as usize, self.crop_h as usize);
    let buf_w = self.width as usize;
    let x0 = self.crop_x as usize;
    let y0 = self.crop_y as usize;
    let m = self.cam2rgb_for(space);
    let src_all: &[f32] = cropped.unwrap_or(&self.rgb);
    // Contiguous when the crop was materialised, strided by the source width when reading through
    // the window. Only the per-row base differs, so it is computed once per row below.
    let row_stride = if cropped.is_some() { cw * stride } else { buf_w * stride };

    let mut out: Vec<f32> = vec![0f32; cw * ch * 3];
    out.par_chunks_mut(cw * 3).enumerate().for_each(|(row, dst)| {
      let base = if cropped.is_some() {
        row * row_stride
      } else {
        (y0 + row) * row_stride + x0 * stride
      };
      for col in 0..cw {
        let s = &src_all[base + col * stride..base + (col + 1) * stride];
        let d = &mut dst[col * 3..col * 3 + 3];
        match self.channels {
          // Monochrome: the crop already replicated the single channel into three components, and
          // a colour matrix over three identical values would only invent a cast. This exemption is
          // why `CameraChannels` exists rather than a bare component count — and it matches the arm
          // the projection had when it lived inside `calibrate`.
          CameraChannels::Monochrome => d.copy_from_slice(&s[0..3]),
          CameraChannels::Three => {
            let (r, g, b) = (s[0], s[1], s[2]);
            d[0] = m[0][0] * r + m[0][1] * g + m[0][2] * b;
            d[1] = m[1][0] * r + m[1][1] * g + m[1][2] * b;
            d[2] = m[2][0] * r + m[2][1] * g + m[2][2] * b;
          }
          CameraChannels::Four => {
            d[0] = m[0][0] * s[0] + m[0][1] * s[1] + m[0][2] * s[2] + m[0][3] * s[3];
            d[1] = m[1][0] * s[0] + m[1][1] * s[1] + m[1][2] * s[2] + m[1][3] * s[3];
            d[2] = m[2][0] * s[0] + m[2][1] * s[1] + m[2][2] * s[2] + m[2][3] * s[3];
          }
        }
      }
    });
    out
  }

  fn cam2rgb_for(&self, space: WorkingSpace) -> [[f32; 4]; 3] {
    match space {
      WorkingSpace::SrgbD65 => self.cam2rgb_srgb,
      WorkingSpace::ProPhotoD50 => self.cam2rgb_prophoto,
    }
  }
}

/// Build the cacheable develop-stage product out of a debayered [intermediate].
///
/// Takes OWNERSHIP of the intermediate: the white balance is per-pixel (each output channel only
/// depends on the same pixel's input channels), so the 3-colour case is scaled IN PLACE and the
/// buffer is zero-copy flattened into the returned object. Allocating a second ~630 MB f32 buffer
/// on a 50 MP frame was the other half of the mid-develop OOM (low-memory-kill).
///
/// Everything non-pixel is resolved here — the D65 camera matrix, both camera→working matrices,
/// the crop rectangle — so nothing downstream needs the `RawImage`. Note what is **not** applied:
/// the OKLab stage, which deliberately sits above this boundary.
///
/// CRITICAL coordinate fix (the "every format develops to Unsupported" bug): `RawImage.crop_area`
/// is in **full-sensor** coordinates, but the demosaic stage already cropped its ROI to
/// `RawImage.active_area` — so the rectangle resolved here is re-based into **active-area**
/// coordinates, exactly as rawler's `CropDefault` does with `crop.adapt(active_area)`. The previous
/// code skipped that re-basing and sliced the smaller buffer at full-sensor offsets, which panicked
/// out of bounds on essentially every real camera file.
///
/// SCALE fix (superpixel's own trap): the buffer can be a **decimated** view of that ROI, and the
/// factor is derived from the dimensions that actually came back — never from a "was superpixel
/// used" flag and never from a hardcoded `0.5` (see [`decimation_factor`]).
pub(crate) fn to_camera_space(
  intermediate: Intermediate,
  image: &RawImage,
  wb: Option<[f32; 4]>,
) -> Result<DemosaicedCameraImage, RawlerFotlabError> {
  // D65 for the OKLab stage regardless of the output space (the round trip is XYZ(D65)-anchored),
  // and each space's own illuminant for the projection it will eventually run through.
  let xyz2cam = resolve_xyz_to_cam(image, Illuminant::D65)?;
  let cam2rgb_srgb = WorkingSpace::SrgbD65.cam2rgb_for(image)?;
  let cam2rgb_prophoto = WorkingSpace::ProPhotoD50.cam2rgb_for(image)?;

  // White balance: explicit override, else rawler default (1.0 if NaN).
  let wb = match wb {
    Some(wb) => wb,
    None => {
      if image.wb_coeffs[0].is_nan() {
        [1.0, 1.0, 1.0, 1.0]
      } else {
        image.wb_coeffs
      }
    }
  };

  // Every arm below is a per-pixel mapping with no cross-pixel dependency, so each one is
  // parallelised with rayon — this is the cost centre the upstream `map_*_to_rgb` helpers cannot
  // cover for us, because they are `pub(crate)` in rawler (`OPTIMZ-PERFRM-000007`,
  // `FOTLAB-RAWLER-000003`).
  let (width, height, channels, rgb) = match intermediate {
    Intermediate::Monochrome(pix) => {
      // No per-channel colour mapping for monochrome; replicate the single channel across RGB.
      // (3x size expansion is unavoidable.) Exposure EV is already baked into the source mosaic by
      // `develop` before demosaic. White balance is *not* applied here either — this arm has never
      // applied it, and inventing a grey balance on a grey image is not a correction.
      let mut rgb: Vec<f32> = vec![0f32; pix.data.len() * 3];
      pix.data
        .par_iter()
        .zip(rgb.par_chunks_exact_mut(3))
        .for_each(|(&v, out)| out.copy_from_slice(&[v, v, v]));
      (pix.width as u32, pix.height as u32, CameraChannels::Monochrome, rgb)
    }
    Intermediate::ThreeColor(mut pixels) => {
      let (w, h) = (pixels.width as u32, pixels.height as u32);
      pixels.pixels_mut().par_iter_mut().for_each(|px| {
        px[0] *= wb[0];
        px[1] *= wb[1];
        px[2] *= wb[2];
      });
      // Reinterpret the same allocation as flat RGB — no ~630 MB copy.
      (w, h, CameraChannels::Three, flatten_rgb3(pixels.into_inner()))
    }
    Intermediate::FourColor(pixels) => {
      // 4 components in, 4 kept: the camera→working matrix has a fourth coefficient per output
      // row, so the fourth channel has to survive until the projection.
      let (w, h) = (pixels.width as u32, pixels.height as u32);
      let mut rgb: Vec<f32> = vec![0f32; pixels.data.len() * 4];
      pixels
        .pixels()
        .par_iter()
        .zip(rgb.par_chunks_exact_mut(4))
        .for_each(|(px, dst)| {
          dst.copy_from_slice(&[px[0] * wb[0], px[1] * wb[1], px[2] * wb[2], px[3] * wb[3]]);
        });
      (w, h, CameraChannels::Four, rgb)
    }
  };

  let (crop_x, crop_y, crop_w, crop_h) = crop_rect(image, width as usize, height as usize)?;

  Ok(DemosaicedCameraImage {
    width,
    height,
    channels,
    rgb,
    xyz2cam,
    cam2rgb_srgb,
    cam2rgb_prophoto,
    crop_x,
    crop_y,
    crop_w,
    crop_h,
  })
}

/// Zero-copy reinterpretation of `Vec<[f32; 3]>` as `Vec<f32>`.
///
/// Sound: `[f32; 3]` has `align_of::<f32>()` and size exactly
/// `3 * size_of::<f32>()` with no padding, so the backing allocation of an
/// array vector is a contiguous run of `len * 3` f32s — same invariants
/// `Vec::from_raw_parts` needs after repointing length/capacity in elements.
fn flatten_rgb3(v: Vec<[f32; 3]>) -> Vec<f32> {
  let mut v = std::mem::ManuallyDrop::new(v);
  // SAFETY: ptr/len/cap stay within the original allocation; the element type
  // change [f32;3] -> f32 preserves layout contiguity and alignment.
  unsafe { Vec::from_raw_parts(v.as_mut_ptr() as *mut f32, v.len() * 3, v.capacity() * 3) }
}

/// Resolve the crop rectangle in the demosaiced buffer's own coordinates,
/// `(x, y, width, height)`; the whole buffer when there is nothing to crop.
///
/// Resolved once, when the cache is built, because it depends only on decoded metadata and on the
/// demosaic's output dimensions — both known at that point and neither changed by anything the
/// cache later serves. A rectangle that cannot fit is reported here, with the numbers that disagree,
/// rather than left to surface as an out-of-bounds slice three stages later.
fn crop_rect(
  image: &RawImage,
  buf_w: usize,
  buf_h: usize,
) -> Result<(u32, u32, u32, u32), RawlerFotlabError> {
  let Some(mut crop) = image.crop_area.or(image.active_area) else {
    return Ok((0, 0, buf_w as u32, buf_h as u32));
  };
  // The demosaic ROI is `active_area` (the whole frame when there is none), so that — not
  // `RawImage.width` — is the full-scale space both the crop rectangle and the intermediate are
  // measured in once the rectangle has been re-based.
  let (roi_w, roi_h) = match image.active_area {
    Some(active_area) => {
      crop = crop.adapt(&active_area);
      (active_area.d.w, active_area.d.h)
    }
    None => (image.width as usize, image.height as usize),
  };

  let factor = decimation_factor(roi_w, roi_h, buf_w, buf_h);
  let (mut x, mut y) = (crop.p.x, crop.p.y);
  let (mut w, mut h) = (crop.width(), crop.height());
  if factor > 1 {
    x /= factor;
    y /= factor;
    w /= factor;
    h /= factor;
  }

  if w == 0 || h == 0 {
    return Ok((0, 0, 0, 0));
  }
  if x + w > buf_w || y + h > buf_h {
    return Err(RawlerFotlabError::Decode(format!(
      "crop rect {w}x{h}+{x}+{y} does not fit the {buf_w}x{buf_h} demosaiced buffer \
       (roi {roi_w}x{roi_h}, decimation factor {factor})"
    )));
  }
  Ok((x as u32, y as u32, w as u32, h as u32))
}

/// Integer decimation factor between the demosaic ROI and the buffer that came back: `1`
/// when they are the same size, `n` when the buffer is that ROI reduced by `n` on both axes.
///
/// Only clean integer decimation is recognised. The quotient is checked by *truncating back*
/// (`roi / n == buf`) rather than by exact divisibility, because that is how the decimator itself
/// works — superpixel emits `roi.d.w >> 1`, so a 3667-wide ROI yields a 1833-wide buffer and
/// `1833 * 2 != 3667` even though the factor is unambiguously 2. Both axes must agree. Anything
/// unrecognised returns `1`, leaving the caller's bounds check to report the mismatch instead of
/// guessing a rectangle.
fn decimation_factor(roi_w: usize, roi_h: usize, buf_w: usize, buf_h: usize) -> usize {
  if buf_w == 0 || buf_h == 0 || buf_w > roi_w || buf_h > roi_h {
    return 1;
  }
  if buf_w == roi_w && buf_h == roi_h {
    return 1;
  }
  let (fw, fh) = (roi_w / buf_w, roi_h / buf_h);
  if fw == fh && fw > 1 && roi_w / fw == buf_w && roi_h / fh == buf_h {
    fw
  } else {
    1
  }
}

#[cfg(test)]
mod tests {
  use super::*;

  const IDENTITY: [[f32; 4]; 3] = [[1.0, 0.0, 0.0, 0.0], [0.0, 1.0, 0.0, 0.0], [0.0, 0.0, 1.0, 0.0]];
  /// A well-conditioned XYZ->camera matrix. It has to be invertible: `OklabBypassMaps::new`
  /// pseudo-inverses it, and a zero matrix would make the OKLab path produce NaNs rather than fail
  /// loudly, which would make a test that merely *ran* the stage quietly meaningless.
  const XYZ2CAM: [[f32; 3]; 4] = [
    [1.0, 0.0, 0.0],
    [0.0, 1.0, 0.0],
    [0.0, 0.0, 1.0],
    [0.0, 0.0, 0.0],
  ];

  /// A cache object with plain matrices, so the tests can state an expected result without going
  /// near a colour matrix or a decoded file.
  fn cache(channels: CameraChannels, width: u32, height: u32, rgb: Vec<f32>) -> DemosaicedCameraImage {
    DemosaicedCameraImage {
      width,
      height,
      channels,
      rgb,
      xyz2cam: XYZ2CAM,
      cam2rgb_srgb: IDENTITY,
      cam2rgb_prophoto: IDENTITY,
      crop_x: 0,
      crop_y: 0,
      crop_w: width,
      crop_h: height,
    }
  }

  fn off() -> OklabSwitches {
    OklabSwitches { highlight_compress_srgb: false, highlight_compress_prophoto: false }
  }

  #[test]
  fn clean_decimation_is_recognised_and_odd_rois_truncate_back() {
    assert_eq!(decimation_factor(6000, 4000, 6000, 4000), 1);
    // 3667 -> 1833: not divisible, but unambiguously a factor of two.
    assert_eq!(decimation_factor(3667, 2449, 1833, 1224), 2);
    // Axes disagreeing is not a decimation this decimator could have produced.
    assert_eq!(decimation_factor(6000, 4000, 3000, 4000), 1);
    // A buffer larger than its ROI is nonsense, not a negative ratio.
    assert_eq!(decimation_factor(100, 100, 200, 200), 1);
  }

  #[test]
  fn cropping_and_projection_commute() {
    // The rectangle is applied before the matrix purely for cost; the two are both per-pixel, so
    // the result must not depend on the order. A red-only 2x1 buffer cropped to its second pixel.
    let mut cropped = cache(CameraChannels::Three, 2, 1, vec![10.0, 0.0, 0.0, 20.0, 0.0, 0.0]);
    cropped.crop_x = 1;
    cropped.crop_w = 1;
    let got = cropped
      .to_working_space(WorkingSpace::SrgbD65, false, off(), false)
      .unwrap();
    assert_eq!(got.rgb, vec![20.0, 0.0, 0.0]);

    let whole = cache(CameraChannels::Three, 2, 1, vec![10.0, 0.0, 0.0, 20.0, 0.0, 0.0])
      .to_working_space(WorkingSpace::SrgbD65, false, off(), false)
      .unwrap();
    assert_eq!(whole.rgb[3..6], [20.0, 0.0, 0.0]);
    assert_eq!(whole.width, 2);
  }

  #[test]
  fn a_crop_that_does_not_fit_is_reported_not_sliced() {
    let mut bad = cache(CameraChannels::Three, 2, 2, vec![0.0; 12]);
    bad.crop_x = 2;
    bad.crop_w = 2;
    let err = bad
      .to_working_space(WorkingSpace::SrgbD65, false, off(), false)
      .unwrap_err();
    assert!(
      err.to_string().contains("does not fit"),
      "expected a bounds diagnostic, got: {err}"
    );
  }

  #[test]
  fn a_skipped_oklab_stage_leaves_the_pixels_identical() {
    let px = vec![0.9f32, 0.7, 0.4, 1.4, 1.2, 0.8];
    // Master off, and again with the master on but the relevant sub-switch off.
    let master_off = cache(CameraChannels::Three, 2, 1, px.clone())
      .to_working_space(WorkingSpace::SrgbD65, false, off(), false)
      .unwrap();
    assert_eq!(master_off.rgb, px);
    let sub_off = OklabSwitches { highlight_compress_srgb: false, highlight_compress_prophoto: true };
    let sub_off = cache(CameraChannels::Three, 2, 1, px.clone())
      .to_working_space(WorkingSpace::SrgbD65, true, sub_off, false)
      .unwrap();
    assert_eq!(sub_off.rgb, px);
  }

  #[test]
  fn the_oklab_sub_switch_is_chosen_by_the_output_space() {
    let switches = OklabSwitches { highlight_compress_srgb: true, highlight_compress_prophoto: false };
    assert!(switches.enabled_for(WorkingSpace::SrgbD65));
    assert!(!switches.enabled_for(WorkingSpace::ProPhotoD50));
    let swapped = OklabSwitches { highlight_compress_srgb: false, highlight_compress_prophoto: true };
    assert!(!swapped.enabled_for(WorkingSpace::SrgbD65));
    assert!(swapped.enabled_for(WorkingSpace::ProPhotoD50));
  }

  #[test]
  fn the_cache_survives_a_render_and_can_be_projected_twice() {
    // Kotlin holds one buffer and hands it back for every later render, so a stage above the
    // cache must not consume or mutate it.
    let held = cache(CameraChannels::Three, 2, 1, vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0]);
    let srgb = OklabSwitches { highlight_compress_srgb: true, highlight_compress_prophoto: false };
    let first = held
      .to_working_space(WorkingSpace::SrgbD65, true, srgb, false)
      .unwrap();
    let second = held
      .to_working_space(WorkingSpace::ProPhotoD50, true, srgb, false)
      .unwrap();
    assert_eq!(first.rgb, second.rgb, "the projection must not depend on render history");
  }

  #[test]
  fn a_fourth_channel_reaches_the_matrix_but_mono_is_exempt_from_it() {
    // out[0] = r + fourth. A three-channel buffer cannot consume that coefficient.
    let mut four = cache(CameraChannels::Four, 1, 1, vec![1.0, 2.0, 3.0, 10.0]);
    let with_fourth = [[1.0, 0.0, 0.0, 1.0], [0.0, 1.0, 0.0, 0.0], [0.0, 0.0, 1.0, 0.0]];
    four.cam2rgb_srgb = with_fourth;
    four.cam2rgb_prophoto = with_fourth;
    let out = four
      .to_working_space(WorkingSpace::SrgbD65, false, off(), false)
      .unwrap();
    assert_eq!(out.rgb, vec![11.0, 2.0, 3.0]);

    // Monochrome stores three identical components and skips the matrix, so a non-identity matrix
    // must leave them alone rather than summing them into a colour cast.
    let mut mono = cache(CameraChannels::Monochrome, 1, 1, vec![5.0, 5.0, 5.0]);
    let summing = [[1.0, 1.0, 1.0, 0.0], [0.0, 1.0, 0.0, 0.0], [0.0, 0.0, 1.0, 0.0]];
    mono.cam2rgb_srgb = summing;
    mono.cam2rgb_prophoto = summing;
    let out = mono
      .to_working_space(WorkingSpace::SrgbD65, false, off(), false)
      .unwrap();
    assert_eq!(out.rgb, vec![5.0, 5.0, 5.0]);
  }

  #[test]
  fn gamut_clipping_is_the_last_step_and_only_when_asked() {
    let hot = vec![1.5f32, -0.2, 0.5];
    let kept = cache(CameraChannels::Three, 1, 1, hot.clone())
      .to_working_space(WorkingSpace::ProPhotoD50, false, off(), false)
      .unwrap();
    assert_eq!(kept.rgb, hot);
    let clipped = cache(CameraChannels::Three, 1, 1, hot)
      .to_working_space(WorkingSpace::ProPhotoD50, false, off(), true)
      .unwrap();
    assert_eq!(clipped.rgb, vec![1.0, 0.0, 0.5]);
  }

  #[test]
  fn a_zero_area_crop_yields_an_empty_image_rather_than_a_panic() {
    let mut empty = cache(CameraChannels::Three, 4, 4, vec![0.0; 48]);
    empty.crop_w = 0;
    empty.crop_h = 0;
    let out = empty
      .to_working_space(WorkingSpace::SrgbD65, false, off(), false)
      .unwrap();
    assert_eq!((out.width, out.height), (0, 0));
    assert!(out.rgb.is_empty());
  }
}