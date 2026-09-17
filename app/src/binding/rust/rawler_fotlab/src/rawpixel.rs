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
//! [`rawimage_to_rawpixel`] is the projection: it captures the decoded samples
//! into [`RawPixelData`] **without re-encoding** (no LJPEG, no pixel pass) and
//! re-expresses the rawler `RawImage` metadata as tags. Crucially, the DNG-ISO
//! spine is **not hand-rolled**: we feed the `RawImage` (with its pixel buffer
//! shrunk to a single sample) to rawler's own `DngWriter::raw_image` — the exact
//! code path that `dnglab`'s `makedng` / rawler's `convert` use — and read the
//! emitted raw sub-IFD back as a flat tag map
//! (`external/dnglab/rawler/src/dng/writer.rs`). A `RawPixel` can therefore later
//! be written back to a real DNG 1:1. rawler-only fields go to `dnglab`.
//!
//! **Zero-pixel-I/O trick**: rawler's `dng_put_raw_uncompressed` only ever
//! streams `rawimage.data` to the output and never checks that its length
//! matches `width*height*cpp`. So after capturing the real samples we swap
//! `data` for a 1-sample placeholder; every shape/format tag is still emitted
//! from the real `width/height/cpp/blacklevel/...` fields, while the throwaway
//! in-memory DNG carries a negligible image strip.

use std::collections::BTreeMap;
use std::io::Cursor;

use rawler::dng::{CropMode, DngCompression, DngPhotometricConversion, DngWriter, DNG_VERSION_V1_4};
use rawler::formats::tiff::{IFD, Value};
use rawler::imgop::Rect;
use rawler::tags::{DngTag, ExifTag, TiffCommonTag};
use rawler::{RawImage, RawImageData};

use crate::RawlerFotlabError;

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
/// Takes ownership of `image`. The pure pixel buffer is captured **first**
/// (uncompressed, no LJPEG re-encode), then `image.data` is shrunk to a single
/// sample so the DNG-ISO spine can be emitted by rawler's own `DngWriter`
/// without streaming the full pixel buffer (see [`isodng_tags`]).
pub(crate) fn rawimage_to_rawpixel(image: RawImage) -> Result<RawPixel, RawlerFotlabError> {
    // Capture the pure pixel buffer before we shrink the source data.
    let buffer = pixel_buffer(&image.data);

    // Shrink the pixel buffer to one sample. rawler's `dng_put_raw_uncompressed`
    // never checks `data.len()` against `width*height*cpp`, so every shape/format
    // tag is still emitted from the real fields while the image strip becomes a
    // single sample. The original samples have already been copied into `buffer`.
    let mut tag_src = image;
    tag_src.data = match &tag_src.data {
        RawImageData::Integer(_) => RawImageData::Integer(vec![0u16]),
        RawImageData::Float(_) => RawImageData::Float(vec![0.0f32]),
    };

    Ok(RawPixel {
        data: RawPixelData { buffer },
        meta: RawPixelMeta {
            isodng: isodng_tags(&tag_src)?,
            dnglab: dnglab_tags(&tag_src),
            fotlab: fotlab_tags(),
        },
    })
}

fn pixel_buffer(data: &RawImageData) -> RawPixelBuffer {
    match data {
        RawImageData::Integer(v) => RawPixelBuffer::Integer(v.clone()),
        RawImageData::Float(v) => RawPixelBuffer::Float(v.clone()),
    }
}

/// Build the flat DNG-ISO tag set (doc R4) by **reusing rawler's own DNG
/// emission** — no hand-rolled tag mapping.
///
/// `image.data` has already been shrunk to a single sample by the caller
/// (`rawimage_to_rawpixel`), so rawler's `DngWriter::raw_image` streams ~0 bytes
/// of image data yet still emits every shape/format tag from the real fields.
/// We then read the raw sub-IFD back and flatten it into `TagsIsoDng`.
///
/// This is the exact code path `dnglab`'s `makedng` / rawler's `convert` use, so
/// the IR's DNG-ISO spine is what rawler would write to a real DNG — and can be
/// written back 1:1.
fn isodng_tags(image: &RawImage) -> Result<TagsIsoDng, RawlerFotlabError> {
    // 1. Build a throwaway DNG in memory via rawler's own writer.
    let mut buf: Cursor<Vec<u8>> = Cursor::new(Vec::new());
    {
        let mut dng = DngWriter::new(&mut buf, DNG_VERSION_V1_4)
            .map_err(|e| RawlerFotlabError::Decode(e.to_string()))?;
        {
            let mut sub = dng.subframe(0);
            sub
                .raw_image(
                    image,
                    CropMode::Best,
                    DngCompression::Uncompressed,
                    DngPhotometricConversion::Original,
                    0,
                )
                .map_err(|e| RawlerFotlabError::Decode(e.to_string()))?;
            sub
                .finalize()
                .map_err(|e| RawlerFotlabError::Decode(e.to_string()))?;
        }
        dng
            .load_base_tags(image)
            .map_err(|e| RawlerFotlabError::Decode(e.to_string()))?;
        dng
            .close()
            .map_err(|e| RawlerFotlabError::Decode(e.to_string()))?;
    }

    // 2. Read the emitted DNG back and harvest the raw sub-IFD tags.
    let mut reader = Cursor::new(buf.into_inner());
    let root = IFD::new_root(&mut reader, 0)
        .map_err(|e| RawlerFotlabError::Decode(e.to_string()))?;

    let sub_offset = root
        .entries()
        .get(&(TiffCommonTag::SubIFDs as u16))
        .map(|e| e.value.force_u32(0))
        .ok_or_else(|| RawlerFotlabError::Decode("built DNG has no SubIFDs".to_string()))?;
    let sub = IFD::new(&mut reader, sub_offset, 0, 0, root.endian, &[])
        .map_err(|e| RawlerFotlabError::Decode(e.to_string()))?;

    let mut map: BTreeMap<u16, Value> = sub
        .entries()
        .iter()
        .map(|(&k, e)| (k, e.value.clone()))
        .collect();

    // 3. Camera identity lives in the root IFD in a real DNG; fold it into the
    //    same DNG-ISO namespace (reusing rawler's `load_base_tags` emission).
    for id in [
        TiffCommonTag::Make as u16,
        TiffCommonTag::Model as u16,
        DngTag::UniqueCameraModel as u16,
    ] {
        if let Some(e) = root.entries().get(&id) {
            map.insert(id, e.value.clone());
        }
    }

    // 4. Orientation is part of the DNG-ISO spine but rawler's raw sub-IFD omits
    //    it; carry it over from the source (single-value form).
    map.insert(
        ExifTag::Orientation as u16,
        Value::from(image.orientation.to_u16()),
    );

    Ok(TagsIsoDng(map))
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
