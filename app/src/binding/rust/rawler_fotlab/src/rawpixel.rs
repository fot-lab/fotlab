//! `RawPixel` — FotLab's canonical decoded-but-undeveloped RAW intermediate,
//! and the `RawImage → RawPixel` projection.
//!
//! **Spec**: `rules/STRUCT/detail/FOTLAB-IPIXEL-000001.md` (canonical RAW
//! intermediate representation). That document's *Bindings* section points back
//! at **this file**; the document is the contract and this file is the Rust
//! implementation of it.
//!
//! Ownership rules enforced here (`FOTLAB-IPIXEL-000001` R2–R6):
//!
//! * [`RawPixelData`] is a **pure pixel buffer** — no shape, no format, no
//!   metadata. It holds only the uncompressed, row-major sample buffer (the
//!   LJPEG-92 *source* data, not a compressed container; `DNGLAB-SURVEY-000004`
//!   §5).
//! * [`RawPixelMeta`] is the single home for every tag, split into exactly three
//!   namespaces: `isodng` (DNG-ISO conformant, **flat**), `dnglab`
//!   (rawler/dnglab-upstream extras, nested allowed), `fotlab` (FotLab-private,
//!   nested allowed).
//! * Geometry/format is *read back* from the tags with the conservative fallback
//!   `isodng` → `fotlab` → `dnglab` ([`read_shape`], doc R2b) — **never** from
//!   `data`, and never defaulted.
//!
//! [`rawimage_to_rawpixel`] is the projection: it copies the decoded samples into
//! [`RawPixelData`] **without re-encoding** (no LJPEG, no pixel pass) and
//! re-expresses the rawler `RawImage` metadata as tags. The DNG-ISO spine mirrors
//! rawler's own `DngWriter::write_rawimage` / `load_base_tags` emission
//! (`external/dnglab/rawler/src/dng/writer.rs`) so a `RawPixel` can later be
//! written back to DNG 1:1; rawler-only fields go to `dnglab`.

use std::collections::BTreeMap;

use rawler::dng::{rect_to_dng_area, DNG_VERSION_V1_4};
use rawler::formats::tiff::{PhotometricInterpretation, Rational, SRational, Value};
use rawler::imgop::xyz::Illuminant;
use rawler::imgop::{Dim2, Point, Rect};
use rawler::rawimage::RawPhotometricInterpretation;
use rawler::tags::{DngTag, ExifTag, TiffCommonTag};
use rawler::{RawImage, RawImageData};

// ---------------------------------------------------------------------------
// The IR (FOTLAB-IPIXEL-000001 R1)
// ---------------------------------------------------------------------------

/// The canonical IR: `data` (pure pixels) + `meta` (all tags).
///
/// See `rules/STRUCT/detail/FOTLAB-IPIXEL-000001.md`.
#[derive(Debug, Clone)]
pub(crate) struct RawPixel {
    pub data: RawPixelData,
    pub meta: RawPixelMeta,
}

/// Near-pure pixel buffer: **no shape, no format, no metadata** (doc R2).
///
/// Even for a zero-copy FFI hand-off the shape is *not* stored here; it is
/// recovered from the tag namespaces ([`read_shape`]).
#[derive(Debug, Clone)]
pub(crate) struct RawPixelData {
    /// Uncompressed, row-major samples. Element width is described by the tags
    /// (`BitsPerSample`), not by the buffer wrapper.
    pub buffer: RawPixelBuffer,
}

/// Uncompressed samples, row-major. `Integer` for the usual 16-bit raws,
/// `Float` for linear-float raws.
#[derive(Debug, Clone)]
pub(crate) enum RawPixelBuffer {
    Integer(Vec<u16>),
    Float(Vec<f32>),
}

/// All interpretation metadata (doc R3): exactly three namespaces, nothing else.
#[derive(Debug, Clone)]
pub(crate) struct RawPixelMeta {
    pub isodng: TagsIsoDng,
    pub dnglab: TagsDngLab,
    pub fotlab: TagsFotLab,
}

/// DNG-ISO tags, **flat**: numeric tag id → typed value (doc R4).
#[derive(Debug, Clone, Default)]
pub(crate) struct TagsIsoDng(pub BTreeMap<u16, Value>);

/// rawler/dnglab-upstream extras, nested allowed (doc R5).
#[derive(Debug, Clone, Default)]
pub(crate) struct TagsDngLab(pub BTreeMap<String, TagValue>);

/// FotLab-private namespace, nested allowed (doc R6).
#[derive(Debug, Clone, Default)]
pub(crate) struct TagsFotLab(pub BTreeMap<String, TagValue>);

/// Self-describing nested value for the non-DNG-ISO namespaces.
#[derive(Debug, Clone)]
pub(crate) enum TagValue {
    Bool(bool),
    I64(i64),
    F64(f64),
    Str(String),
    Bytes(Vec<u8>),
    List(Vec<TagValue>),
    Map(BTreeMap<String, TagValue>),
}

impl TagValue {
    fn str(v: impl Into<String>) -> Self {
        TagValue::Str(v.into())
    }

    fn int(v: i64) -> Self {
        TagValue::I64(v)
    }

    fn float(v: f64) -> Self {
        TagValue::F64(v)
    }

    fn map(entries: impl IntoIterator<Item = (&'static str, TagValue)>) -> Self {
        TagValue::Map(entries.into_iter().map(|(k, v)| (k.to_string(), v)).collect())
    }
}

// ---------------------------------------------------------------------------
// Shape, read from the tags (FOTLAB-IPIXEL-000001 R2b)
// ---------------------------------------------------------------------------

/// Geometry/format resolved from tags. This is *not* part of the IR; it is a
/// read-time projection (doc R2b).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct Shape {
    pub width: u32,
    pub height: u32,
    pub cpp: u32,
}

// Fallback hint keys for the `fotlab` / `dnglab` namespaces (fallbacks 2 and 3).
const FOTLAB_WIDTH: &str = "shape.width";
const FOTLAB_HEIGHT: &str = "shape.height";
const FOTLAB_CPP: &str = "shape.cpp";
const DNGLAB_WIDTH: &str = "rawimage.width";
const DNGLAB_HEIGHT: &str = "rawimage.height";
const DNGLAB_CPP: &str = "rawimage.cpp";

/// Resolve width/height/cpp for `pixel` using the conservative fallback
/// `isodng` → `fotlab` → `dnglab` (doc R2b).
///
/// Returns `None` when no namespace yields a complete geometry; callers MUST
/// error rather than guess a default shape.
pub(crate) fn read_shape(pixel: &RawPixel) -> Option<Shape> {
    // 1. isodng — authoritative when present (written whenever a RawPixel is
    //    emitted; doc R4).
    if let (Some(width), Some(height), Some(cpp)) = (
        iso_u32(&pixel.meta.isodng, TiffCommonTag::ImageWidth as u16),
        iso_u32(&pixel.meta.isodng, TiffCommonTag::ImageLength as u16),
        iso_u32(&pixel.meta.isodng, TiffCommonTag::SamplesPerPixel as u16),
    ) {
        return Some(Shape { width, height, cpp });
    }

    // 2. fotlab, then 3. dnglab.
    shape_from_map(&pixel.meta.fotlab.0, FOTLAB_WIDTH, FOTLAB_HEIGHT, FOTLAB_CPP).or_else(|| {
        shape_from_map(&pixel.meta.dnglab.0, DNGLAB_WIDTH, DNGLAB_HEIGHT, DNGLAB_CPP)
    })
}

fn iso_u32(tags: &TagsIsoDng, tag: u16) -> Option<u32> {
    tags.0.get(&tag).and_then(value_u32)
}

fn value_u32(value: &Value) -> Option<u32> {
    match value {
        Value::Byte(v) => v.first().map(|x| u32::from(*x)),
        Value::Short(v) => v.first().map(|x| u32::from(*x)),
        Value::Long(v) => v.first().copied(),
        Value::SShort(v) => v.first().map(|x| *x as u32),
        Value::SLong(v) => v.first().map(|x| *x as u32),
        _ => None,
    }
}

fn shape_from_map(
    map: &BTreeMap<String, TagValue>,
    width_key: &str,
    height_key: &str,
    cpp_key: &str,
) -> Option<Shape> {
    Some(Shape {
        width: map_u32(map, width_key)?,
        height: map_u32(map, height_key)?,
        cpp: map_u32(map, cpp_key)?,
    })
}

fn map_u32(map: &BTreeMap<String, TagValue>, key: &str) -> Option<u32> {
    match map.get(key)? {
        TagValue::I64(v) => u32::try_from(*v).ok(),
        TagValue::F64(v) => Some(*v as u32),
        _ => None,
    }
}

// ---------------------------------------------------------------------------
// Projection: rawler RawImage -> RawPixel
// ---------------------------------------------------------------------------

/// Project a decoded rawler [`RawImage`] into the canonical [`RawPixel`] IR.
///
/// The pixel buffer is copied as-is (uncompressed, no LJPEG re-encode); every
/// non-pixel property becomes a tag in one of the three namespaces.
pub(crate) fn rawimage_to_rawpixel(image: &RawImage) -> RawPixel {
    RawPixel {
        data: RawPixelData {
            buffer: pixel_buffer(&image.data),
        },
        meta: RawPixelMeta {
            isodng: isodng_tags(image),
            dnglab: dnglab_tags(image),
            fotlab: fotlab_tags(),
        },
    }
}

fn pixel_buffer(data: &RawImageData) -> RawPixelBuffer {
    match data {
        RawImageData::Integer(v) => RawPixelBuffer::Integer(v.clone()),
        RawImageData::Float(v) => RawPixelBuffer::Float(v.clone()),
    }
}

/// Build the flat DNG-ISO tag set (doc R4).
///
/// Mirrors rawler's `DngWriter::write_rawimage` (Original photometric path) and
/// `load_base_tags`, plus the DNG-version spine from `DngWriter::new`, so the
/// result can be written back to a DNG 1:1. `Compression` is intentionally left
/// out (it is a serialization-boundary concern, doc C3); `DefaultScale` /
/// `BestQualityScale` are left to DNG defaults.
fn isodng_tags(image: &RawImage) -> TagsIsoDng {
    let img = image;
    let mut t: BTreeMap<u16, Value> = BTreeMap::new();

    // DNG version spine (DngWriter::new).
    t.insert(DngTag::DNGVersion as u16, Value::from(DNG_VERSION_V1_4));
    t.insert(DngTag::DNGBackwardVersion as u16, Value::from(DNG_VERSION_V1_4));

    // Geometry/format: the authoritative shape home (doc R2b).
    t.insert(TiffCommonTag::ImageWidth as u16, Value::from(img.width as u32));
    t.insert(TiffCommonTag::ImageLength as u16, Value::from(img.height as u32));
    t.insert(TiffCommonTag::SamplesPerPixel as u16, Value::from(img.cpp as u16));
    t.insert(TiffCommonTag::BitsPerSample as u16, Value::from(img.bps as u16));
    t.insert(TiffCommonTag::PlanarConfiguration as u16, Value::from(1_u16));
    t.insert(ExifTag::Orientation as u16, Value::from(img.orientation.to_u16()));

    // Active area / crop (CropMode::Best, mirrors write_rawimage).
    let full = Rect::new(Point::new(0, 0), Dim2::new(img.width, img.height));
    let active = img.active_area.unwrap_or(full);
    let crop = img.crop_area.unwrap_or(active);
    t.insert(DngTag::ActiveArea as u16, Value::from(rect_to_dng_area(&active)));
    t.insert(
        DngTag::DefaultCropOrigin as u16,
        Value::from([
            crop.p.x.saturating_sub(active.p.x) as u16,
            crop.p.y.saturating_sub(active.p.y) as u16,
        ]),
    );
    t.insert(
        DngTag::DefaultCropSize as u16,
        Value::from([crop.d.w as u16, crop.d.h as u16]),
    );

    // White level.
    if img.whitelevel.0.iter().all(|x| *x <= u16::MAX as u32) {
        let v: Vec<u16> = img.whitelevel.0.iter().map(|x| *x as u16).collect();
        t.insert(DngTag::WhiteLevel as u16, Value::from(v.as_slice()));
    } else {
        t.insert(DngTag::WhiteLevel as u16, Value::from(img.whitelevel.0.as_slice()));
    }

    // Black level (shifted into the active area, mirrors write_rawimage).
    let black = img.blacklevel.shift(active.p.x, active.p.y);
    t.insert(
        DngTag::BlackLevelRepeatDim as u16,
        Value::from([black.height as u16, black.width as u16]),
    );
    if black.levels.iter().all(|r| r.d == 1) {
        let as_u32: Vec<u32> = black.levels.iter().map(|r| r.n).collect();
        if as_u32.iter().all(|x| *x <= u16::MAX as u32) {
            let as_u16: Vec<u16> = as_u32.iter().map(|x| *x as u16).collect();
            t.insert(DngTag::BlackLevel as u16, Value::from(as_u16.as_slice()));
        } else {
            t.insert(DngTag::BlackLevel as u16, Value::from(as_u32.as_slice()));
        }
    } else {
        t.insert(DngTag::BlackLevel as u16, Value::from(black.levels.as_slice()));
    }

    if !img.blackareas.is_empty() {
        let masked: Vec<u16> = img.blackareas.iter().flat_map(rect_to_dng_area).collect();
        t.insert(DngTag::MaskedAreas as u16, Value::from(masked.as_slice()));
    }

    // Photometric interpretation + CFA (mirrors write_rawimage).
    match &img.photometric {
        RawPhotometricInterpretation::BlackIsZero => {
            t.insert(
                TiffCommonTag::PhotometricInt as u16,
                Value::from(PhotometricInterpretation::BlackIsZero),
            );
        }
        RawPhotometricInterpretation::Cfa(config) => {
            let cfa = config.cfa.shift(active.p.x, active.p.y);
            t.insert(
                TiffCommonTag::CFARepeatPatternDim as u16,
                Value::from([cfa.width as u16, cfa.height as u16]),
            );
            t.insert(
                TiffCommonTag::CFAPattern as u16,
                Value::from(cfa.flat_pattern().as_slice()),
            );
            t.insert(
                TiffCommonTag::PhotometricInt as u16,
                Value::from(PhotometricInterpretation::CFA),
            );
            t.insert(DngTag::CFAPlaneColor as u16, Value::from(&config.colors));
            t.insert(DngTag::CFALayout as u16, Value::from(1_u16));
        }
        RawPhotometricInterpretation::LinearRaw => {
            t.insert(
                TiffCommonTag::PhotometricInt as u16,
                Value::from(PhotometricInterpretation::LinearRaw),
            );
        }
    }

    // White balance (AsShotNeutral) + colour matrices (mirrors write_rawimage).
    if img.cpp > 1 || matches!(img.photometric, RawPhotometricInterpretation::Cfa(_)) {
        t.insert(
            DngTag::AsShotNeutral as u16,
            Value::from(as_shot_neutral(img).as_slice()),
        );

        let mut matrices = img.color_matrix.clone();
        if let Some(first_key) = matrices.keys().next().cloned() {
            let (illu1, m1) = matrices
                .remove_entry(&Illuminant::A)
                .or_else(|| matrices.remove_entry(&first_key))
                .expect("no colour matrix found");
            t.insert(DngTag::CalibrationIlluminant1 as u16, Value::from(u16::from(illu1)));
            t.insert(DngTag::ColorMatrix1 as u16, Value::from(matrix_to_srational(&m1).as_slice()));
            if let Some((illu2, m2)) = matrices
                .remove_entry(&Illuminant::D65)
                .or_else(|| matrices.remove_entry(&Illuminant::D50))
            {
                t.insert(DngTag::CalibrationIlluminant2 as u16, Value::from(u16::from(illu2)));
                t.insert(DngTag::ColorMatrix2 as u16, Value::from(matrix_to_srational(&m2).as_slice()));
            }
        }
    }

    // Camera identity (mirrors load_base_tags).
    t.insert(TiffCommonTag::Make as u16, Value::from(img.clean_make.as_str()));
    t.insert(TiffCommonTag::Model as u16, Value::from(img.clean_model.as_str()));
    t.insert(
        DngTag::UniqueCameraModel as u16,
        Value::from(format!("{} {}", img.clean_make, img.clean_model)),
    );

    TagsIsoDng(t)
}

/// RAW white-balance coefficients → DNG `AsShotNeutral` (reciprocals), mirroring
/// rawler's `wbcoeff_to_tiff_value`.
fn as_shot_neutral(image: &RawImage) -> Vec<Rational> {
    let wb = &image.wb_coeffs;
    let recip = |i: usize| Rational::new_f32(1.0 / wb[i], 100_000);
    match &image.photometric {
        RawPhotometricInterpretation::BlackIsZero => vec![Rational::new(1, 1)],
        RawPhotometricInterpretation::Cfa(config) => {
            let mut v = vec![recip(0), recip(1), recip(2)];
            if config.cfa.unique_colors() == 4 {
                v.push(recip(3));
            }
            v
        }
        RawPhotometricInterpretation::LinearRaw => match image.cpp {
            3 => vec![recip(0), recip(1), recip(2)],
            _ => vec![Rational::new(1, 1)],
        },
    }
}

/// XYZ→camera matrix → DNG `ColorMatrix` (signed rationals), mirroring rawler's
/// `matrix_to_tiff_value`.
fn matrix_to_srational(matrix: &[f32]) -> Vec<SRational> {
    let d = 10_000_i32;
    matrix
        .iter()
        .map(|a| SRational::new((a * d as f32) as i32, d))
        .collect()
}

/// rawler/dnglab-upstream extras that have no DNG-ISO slot (doc R5).
///
/// Also carries the `rawimage.*` shape keys used by the R2b fallback (3).
fn dnglab_tags(image: &RawImage) -> TagsDngLab {
    let mut m: BTreeMap<String, TagValue> = BTreeMap::new();
    m.insert(DNGLAB_WIDTH.to_string(), TagValue::int(image.width as i64));
    m.insert(DNGLAB_HEIGHT.to_string(), TagValue::int(image.height as i64));
    m.insert(DNGLAB_CPP.to_string(), TagValue::int(image.cpp as i64));
    m.insert("rawimage.bps".to_string(), TagValue::int(image.bps as i64));
    m.insert("rawimage.make".to_string(), TagValue::str(image.make.clone()));
    m.insert("rawimage.model".to_string(), TagValue::str(image.model.clone()));
    m.insert(
        "rawimage.wb_coeffs".to_string(),
        TagValue::List(image.wb_coeffs.iter().map(|v| TagValue::float(f64::from(*v))).collect()),
    );
    if let Some(width) = image.fuji_rotation_width {
        m.insert("rawimage.fuji_rotation_width".to_string(), TagValue::int(width as i64));
    }
    if let Some(area) = &image.active_area {
        m.insert("rawimage.active_area".to_string(), rect_value(area));
    }
    if let Some(area) = &image.crop_area {
        m.insert("rawimage.crop_area".to_string(), rect_value(area));
    }
    if !image.blackareas.is_empty() {
        m.insert(
            "rawimage.blackareas".to_string(),
            TagValue::List(image.blackareas.iter().map(rect_value).collect()),
        );
    }
    TagsDngLab(m)
}

fn rect_value(rect: &Rect) -> TagValue {
    TagValue::map([
        ("x", TagValue::int(rect.p.x as i64)),
        ("y", TagValue::int(rect.p.y as i64)),
        ("w", TagValue::int(rect.d.w as i64)),
        ("h", TagValue::int(rect.d.h as i64)),
    ])
}

/// FotLab-private provenance (doc R6).
fn fotlab_tags() -> TagsFotLab {
    let mut m: BTreeMap<String, TagValue> = BTreeMap::new();
    m.insert("provenance.producer".to_string(), TagValue::str("rawler_fotlab"));
    m.insert(
        "pipeline.stage".to_string(),
        TagValue::str("decoded-undeveloped"),
    );
    TagsFotLab(m)
}
