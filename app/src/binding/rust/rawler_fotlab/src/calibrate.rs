//! Calibrate glue — the **working-space** half of the develop trunk: which RGB primaries
//! (and white point) a developed result lives in, and how the camera colour matrix is
//! resolved for it.
//!
//! Two spaces are supported (`rules/REVIEW/detail/FOTLAB-RAWLER-000005.md`):
//!
//! * [`WorkingSpace::SrgbD65`] — presentation. Finished to sRGB (transfer function + clip)
//!   at PNG encode time in `bound`, never here.
//! * [`WorkingSpace::ProPhotoD50`] — editing, for the rawalchemy pipeline. Wide gamut and
//!   **unclamped**: negatives and >1 survive into the returned buffer on purpose.
//!
//! The trunk no longer forks at the entry: `space` is *derived* from the caller's
//! `PipelineStages` (grade on → ProPhoto D50, grade off → sRGB D65) and the pixel work itself
//! lives in [`crate::camera_space`] — the camera-space half that is cacheable and the
//! working-space projection that is not. What stays here is the shared, stage-independent
//! part: the space enum, its illuminant / forward matrix, and [`resolve_xyz_to_cam`], which
//! the white-balance Kelvin helpers (`wb::as_shot_color_temp_kelvin`) also read so a custom
//! multiplier is always computed against the exact matrix it renders through.
//!
//! rawler's own `map_3ch_to_rgb` / `map_4ch_to_rgb` are `pub(crate)`, so the projection is
//! replicated in `camera_space` using rawler's *public* matrix primitives (`multiply`,
//! `normalize`, `pseudo_inverse`). The colour matrix is always taken from rawler's resolved
//! `RawImage.color_matrix` (Bradford-adapted when only another illuminant is available),
//! exactly as rawler does. Exposure compensation is **not** applied here — it is applied as
//! the linear gain `2^exposure_ev` to the single-channel mosaic *before* demosaic in
//! `develop`, since demosaic is linear and the gain is channel-uniform, so shifting it earlier
//! is numerically identical.

use rawler::imgop::chromatic_adaption::adapt_bradford;
use rawler::imgop::matrix::{multiply, normalize, pseudo_inverse};
use rawler::imgop::xyz::{Illuminant, SRGB_TO_XYZ_D65, XYZ_TO_PROFOTORGB_D50};
use rawler::RawImage;

use crate::RawlerFotlabError;

/// Which RGB primaries (and white point) the developed result lives in.
///
/// The two paths exist because they have opposite requirements
/// (`rules/REVIEW/detail/FOTLAB-RAWLER-000005.md`):
///
/// * [`WorkingSpace::SrgbD65`] — the *presentation* path. Small gamut, but it is what a
///   display can actually show, so this is the space the UI PNG is finished in (transfer
///   function and gamut mapping included, applied at PNG encode time — see
///   `bound::rawlerimagedeveloped_to_png`).
/// * [`WorkingSpace::ProPhotoD50`] — the *editing* path handed to the rawalchemy pipeline.
///   Wide gamut: colours outside sRGB survive here. It is deliberately **not** clipped —
///   negative and >1 components are legitimate and only get resolved at final export.
///   D50 matches rawalchemy and RawTherapee, so no chromatic-adaptation bridge is needed.
///
/// There is exactly one trunk now: which space a render lands in is read off the caller's
/// `PipelineStages` (see `PipelineStages::working_space`), never off which entry point was
/// called.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WorkingSpace {
    /// Linear sRGB, white point D65.
    SrgbD65,
    /// Linear ProPhoto RGB, white point D50.
    ProPhotoD50,
}

impl WorkingSpace {
    /// The illuminant the camera colour matrix must be adapted to for this space.
    pub(crate) fn illuminant(self) -> Illuminant {
        match self {
            WorkingSpace::SrgbD65 => Illuminant::D65,
            WorkingSpace::ProPhotoD50 => Illuminant::D50,
        }
    }

    /// Forward matrix from this working space to XYZ, at this space's own white point.
    ///
    /// `sRGB → XYZ` is a published constant; rawler only ships `XYZ → ProPhoto`, so that one
    /// is inverted here (`pseudo_inverse` on a 3×3 is negligible next to the per-pixel loop).
    fn to_xyz_matrix(self) -> [[f32; 3]; 3] {
        match self {
            WorkingSpace::SrgbD65 => SRGB_TO_XYZ_D65,
            WorkingSpace::ProPhotoD50 => pseudo_inverse(XYZ_TO_PROFOTORGB_D50),
        }
    }

    /// The camera → working-space matrix, anchored on this space's illuminant.
    ///
    /// Four coefficients per output row, not three: rawler's `pseudo_inverse` runs on the
    /// 4-row XYZ→camera matrix and returns `[[f32; 4]; 3]`, so a four-colour CFA genuinely has
    /// a fourth camera channel feeding each output row. A 3-channel buffer simply uses the
    /// first three.
    ///
    /// Resolved once per image and then carried **inside** the `DemosaicedCameraImage`, so the
    /// projection afterwards is a pure per-pixel multiply that needs no `RawImage` beside it
    /// (`crate::camera_space`).
    pub(crate) fn cam2rgb_for(self, image: &RawImage) -> Result<[[f32; 4]; 3], RawlerFotlabError> {
        let xyz2cam = resolve_xyz_to_cam(image, self.illuminant())?;
        let rgb2cam = normalize(multiply(&xyz2cam, &self.to_xyz_matrix()));
        Ok(pseudo_inverse(rgb2cam))
    }
}

/// Resolve the camera color matrix (XYZ→camera, `[[f32;3];4]`, RGBE rows) at the
/// requested reference [illuminant]: rawler's preferred-matrix lookup, Bradford
/// adapted from the stored illuminant when it differs, identity fallback. This is
/// the matrix the calibrate render pairs its white-balance multipliers with, and
/// the same resolution the white-balance Kelvin helpers use
/// (`wb::as_shot_color_temp_kelvin`), so a custom multiplier is always computed
/// against the exact matrix it will be rendered through.
pub(crate) fn resolve_xyz_to_cam(
    image: &RawImage,
    illuminant: Illuminant,
) -> Result<[[f32; 3]; 4], RawlerFotlabError> {
    let mut xyz2cam: [[f32; 3]; 4] = [[0.0; 3]; 4];
    let (illu, matrix) = image
        .color_matrix_find_first([
            Illuminant::D65,
            Illuminant::A,
            Illuminant::B,
            Illuminant::C,
            Illuminant::D50,
            Illuminant::D55,
            Illuminant::D75,
            Illuminant::Daylight,
            Illuminant::Flash,
        ])
        .unwrap_or_else(|| (illuminant, vec![1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]));
    let target_matrix: Vec<f32> = if illu == illuminant {
        matrix
    } else {
        match matrix.len() {
            9 => adapt_bradford(&illu, &illuminant, &transform_1d_3x3(&matrix))
                .into_iter()
                .flatten()
                .collect(),
            _ => return Err(RawlerFotlabError::Decode("color matrix has unexpected size".to_string())),
        }
    };
    assert_eq!(target_matrix.len() % 3, 0);
    let components = target_matrix.len() / 3;
    for i in 0..components {
        for j in 0..3 {
            xyz2cam[i][j] = target_matrix[i * 3 + j];
        }
    }
    Ok(xyz2cam)
}

/// Tiny helper replicating `rawler::imgop::matrix::transform_1d::<3,3>` — reshapes
/// a 9-element flat colour matrix into `[[f32;3];3]` for `adapt_bradford`.
fn transform_1d_3x3(matrix: &[f32]) -> [[f32; 3]; 3] {
    let mut out = [[0.0f32; 3]; 3];
    for (i, v) in matrix.iter().copied().enumerate() {
        out[i / 3][i % 3] = v;
    }
    out
}