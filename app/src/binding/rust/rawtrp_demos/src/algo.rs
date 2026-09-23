//! Algorithm catalogue and the two decoupling name maps.
//!
//! Two algorithm families are selectable, and the UI must not care which is
//! which:
//!
//! * **RAWLER** — rawler's own upstream demosaics (`rawler::imgop::sensor`),
//!   which this crate does not implement, only *describes*. Their original
//!   names (`Ppg`, `Bilinear4Channel`, …) are wrapped behind a dictionary so the
//!   standard candidate name is `RAWLER …`.
//! * **RAWTRP** — the kernels ported in this crate. They keep their upstream
//!   RawTherapee name (`amaze`, `vng4`, …) and are likewise mapped through a
//!   dictionary to `RAWTRP …`.
//!
//! [`candidates`] concatenates the two mapped lists — that concatenation *is*
//! the list handed to Kotlin, in the same order, and it is also how
//! `DemosaicAlgorithm` is extended on the binding side (existing variants first,
//! ported ones appended).

/// Which sensor families an algorithm can run on — the UI greys out the rest.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SensorKind {
  /// 2x2-periodic Bayer CFA (and 4-colour variants where supported).
  Bayer,
  /// 6x6 Fujifilm X-Trans CFA.
  XTrans,
}

/// One entry of a decoupling dictionary: the decoupled *original* name and the
/// *standard* candidate name shown to the user.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct AlgoName {
  /// Name as known to the owning library (rawler variant name / upstream RT
  /// method string). Never shown to the user.
  pub original: &'static str,
  /// Standard candidate name, always prefixed `RAWLER ` or `RAWTRP `.
  pub standard: &'static str,
}

/// RAWLER dictionary — rawler's own demosaics, original name -> standard name.
pub const RAWLER_NAMES: &[AlgoName] = &[
  AlgoName { original: "Default", standard: "RAWLER Default" },
  AlgoName { original: "Ppg", standard: "RAWLER Ppg" },
  AlgoName { original: "Bilinear4Channel", standard: "RAWLER Bilinear4Channel" },
  AlgoName { original: "XTransBilinear", standard: "RAWLER XTransBilinear" },
];

/// RAWTRP dictionary — ported Bayer kernels, upstream method string -> standard
/// name. The upstream strings are the UI identifiers of
/// `RAWParams::BayerSensor::Method` (`RAWTRP-DECODE-000001` §1.1).
pub const RAWTRP_BAYER_NAMES: &[AlgoName] = &[
  AlgoName { original: "bilinear", standard: "RAWTRP bilinear" },
  AlgoName { original: "vng4", standard: "RAWTRP vng4" },
  AlgoName { original: "rcd", standard: "RAWTRP rcd" },
  AlgoName { original: "ahd", standard: "RAWTRP ahd" },
  AlgoName { original: "eahd", standard: "RAWTRP eahd" },
  AlgoName { original: "hphd", standard: "RAWTRP hphd" },
  AlgoName { original: "amaze", standard: "RAWTRP amaze" },
  AlgoName { original: "lmmse", standard: "RAWTRP lmmse" },
  AlgoName { original: "igv", standard: "RAWTRP igv" },
  AlgoName { original: "dcb", standard: "RAWTRP dcb" },
  AlgoName { original: "fast", standard: "RAWTRP fast" },
  AlgoName { original: "amaze_bilinear", standard: "RAWTRP amaze_bilinear" },
  AlgoName { original: "amaze_vng4", standard: "RAWTRP amaze_vng4" },
  AlgoName { original: "rcd_bilinear", standard: "RAWTRP rcd_bilinear" },
  AlgoName { original: "rcd_vng4", standard: "RAWTRP rcd_vng4" },
  AlgoName { original: "dcb_bilinear", standard: "RAWTRP dcb_bilinear" },
  AlgoName { original: "dcb_vng4", standard: "RAWTRP dcb_vng4" },
];

/// RAWTRP dictionary — ported X-Trans kernels (`RAWTRP-DECODE-000001` §1.2).
pub const RAWTRP_XTRANS_NAMES: &[AlgoName] = &[
  AlgoName { original: "one_pass", standard: "RAWTRP one_pass" },
  AlgoName { original: "three_pass", standard: "RAWTRP three_pass" },
  AlgoName { original: "two_pass", standard: "RAWTRP two_pass" },
  AlgoName { original: "four_pass", standard: "RAWTRP four_pass" },
  AlgoName { original: "fast", standard: "RAWTRP xtrans_fast" },
];

/// Upstream Bayer method strings whose kernel is **already ported and wired** in
/// [`crate::demosaic_bayer`]. [`candidates`] advertises only these, so the UI can
/// never offer a path that would come back as
/// [`crate::Error::UnsupportedAlgo`]. Entries are flipped on as each kernel's arm
/// lands, one kernel per change (`FOTLAB-NATIVE-000004` C6).
pub const IMPLEMENTED_BAYER: &[&str] = &["bilinear", "vng4", "rcd"];

/// As [`IMPLEMENTED_BAYER`], for the ported X-Trans kernels.
pub const IMPLEMENTED_XTRANS: &[&str] = &[];

/// Look a standard name up in a dictionary by its original name.
fn standard_of(dict: &'static [AlgoName], original: &str) -> &'static str {
  dict
    .iter()
    .find(|e| e.original == original)
    .map_or("<unmapped>", |e| e.standard)
}

/// A ported Bayer kernel (or a `dual_demosaic_RT` hybrid of two of them).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BayerAlgo {
  Bilinear,
  Vng4,
  Rcd,
  Ahd,
  Eahd,
  Hphd,
  Amaze,
  Lmmse,
  Igv,
  Dcb,
  Fast,
  AmazeBilinear,
  AmazeVng4,
  RcdBilinear,
  RcdVng4,
  DcbBilinear,
  DcbVng4,
}

impl BayerAlgo {
  /// Upstream method string (the dictionary key).
  #[must_use]
  pub fn original_name(self) -> &'static str {
    match self {
      Self::Bilinear => "bilinear",
      Self::Vng4 => "vng4",
      Self::Rcd => "rcd",
      Self::Ahd => "ahd",
      Self::Eahd => "eahd",
      Self::Hphd => "hphd",
      Self::Amaze => "amaze",
      Self::Lmmse => "lmmse",
      Self::Igv => "igv",
      Self::Dcb => "dcb",
      Self::Fast => "fast",
      Self::AmazeBilinear => "amaze_bilinear",
      Self::AmazeVng4 => "amaze_vng4",
      Self::RcdBilinear => "rcd_bilinear",
      Self::RcdVng4 => "rcd_vng4",
      Self::DcbBilinear => "dcb_bilinear",
      Self::DcbVng4 => "dcb_vng4",
    }
  }

  /// Standard candidate name (`RAWTRP …`).
  #[must_use]
  pub fn standard_name(self) -> &'static str {
    standard_of(RAWTRP_BAYER_NAMES, self.original_name())
  }

  /// Whether this hybrid needs `dual_demosaic_RT` rather than a single kernel.
  #[must_use]
  pub fn is_dual(self) -> bool {
    matches!(
      self,
      Self::AmazeBilinear | Self::AmazeVng4 | Self::RcdBilinear | Self::RcdVng4 | Self::DcbBilinear | Self::DcbVng4
    )
  }
}

/// A ported X-Trans kernel.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum XTransAlgo {
  OnePass,
  ThreePass,
  Fast,
  TwoPass,
  FourPass,
}

impl XTransAlgo {
  /// Upstream method string (the dictionary key).
  #[must_use]
  pub fn original_name(self) -> &'static str {
    match self {
      Self::OnePass => "one_pass",
      Self::ThreePass => "three_pass",
      Self::Fast => "fast",
      Self::TwoPass => "two_pass",
      Self::FourPass => "four_pass",
    }
  }

  /// Standard candidate name (`RAWTRP …`).
  #[must_use]
  pub fn standard_name(self) -> &'static str {
    standard_of(RAWTRP_XTRANS_NAMES, self.original_name())
  }

  /// Whether this variant is the `dual_demosaic_RT` hybrid rather than a single
  /// pass count.
  #[must_use]
  pub fn is_dual(self) -> bool {
    matches!(self, Self::TwoPass | Self::FourPass)
  }
}

/// A selectable candidate, ready to hand to the UI.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Candidate {
  /// Stable identifier the UI sends back (never localised).
  pub id: &'static str,
  /// Standard display name (`RAWLER …` / `RAWTRP …`).
  pub label: &'static str,
  /// Sensor families this candidate applies to.
  pub kind: SensorKind,
}

/// The concatenated candidate list: RAWLER entries (in rawler's own order)
/// followed by RAWTRP entries (Bayer then X-Trans), exactly as
/// `DemosaicAlgorithm` is extended on the binding side.
#[must_use]
pub fn candidates() -> Vec<Candidate> {
  let mut out: Vec<Candidate> = Vec::with_capacity(RAWLER_NAMES.len() + RAWTRP_BAYER_NAMES.len() + RAWTRP_XTRANS_NAMES.len());

  // RAWLER half — ids keep the original variant names so the binding side can
  // map them straight back onto `rawler`'s own enum.
  for e in RAWLER_NAMES {
    let (id, kind) = match e.original {
      "Default" => ("rawler:default", SensorKind::Bayer),
      "Ppg" => ("rawler:ppg", SensorKind::Bayer),
      "Bilinear4Channel" => ("rawler:bilinear4", SensorKind::Bayer),
      "XTransBilinear" => ("rawler:xtrans_bilinear", SensorKind::XTrans),
      other => (other, SensorKind::Bayer),
    };
    out.push(Candidate { id, label: e.standard, kind });
  }

  // RAWTRP half — Bayer kernels (id = `rawtrp:<upstream method string>`).
  for e in RAWTRP_BAYER_NAMES {
    let id = match e.original {
      "bilinear" => "rawtrp:bilinear",
      "vng4" => "rawtrp:vng4",
      "rcd" => "rawtrp:rcd",
      "ahd" => "rawtrp:ahd",
      "eahd" => "rawtrp:eahd",
      "hphd" => "rawtrp:hphd",
      "amaze" => "rawtrp:amaze",
      "lmmse" => "rawtrp:lmmse",
      "igv" => "rawtrp:igv",
      "dcb" => "rawtrp:dcb",
      "fast" => "rawtrp:fast",
      "amaze_bilinear" => "rawtrp:amaze_bilinear",
      "amaze_vng4" => "rawtrp:amaze_vng4",
      "rcd_bilinear" => "rawtrp:rcd_bilinear",
      "rcd_vng4" => "rawtrp:rcd_vng4",
      "dcb_bilinear" => "rawtrp:dcb_bilinear",
      "dcb_vng4" => "rawtrp:dcb_vng4",
      other => other,
    };
    out.push(Candidate { id, label: e.standard, kind: SensorKind::Bayer });
  }

  // RAWTRP half — X-Trans kernels.
  for e in RAWTRP_XTRANS_NAMES {
    let id = match e.original {
      "one_pass" => "rawtrp:one_pass",
      "three_pass" => "rawtrp:three_pass",
      "two_pass" => "rawtrp:two_pass",
      "four_pass" => "rawtrp:four_pass",
      "fast" => "rawtrp:xtrans_fast",
      other => other,
    };
    out.push(Candidate { id, label: e.standard, kind: SensorKind::XTrans });
  }

  // Advertise only kernels that are actually ported and wired, so the UI can never
  // offer a path that would come back as `Error::UnsupportedAlgo`
  // (`FOTLAB-NATIVE-000004` C6 — one kernel per change).
  //
  // The filter is keyed on the *original* upstream name, which is why the two
  // "fast" entries (Bayer `fast_demosaic` and X-Trans `fast_xtrans_interpolate`)
  // are disambiguated by `kind` rather than by name alone — and why the X-Trans id
  // `rawtrp:xtrans_fast` is folded back to `fast`.
  out.retain(|c| {
    let Some(stripped) = c.id.strip_prefix("rawtrp:") else {
      return true; // RAWLER candidates always exist
    };
    let original = if stripped == "xtrans_fast" { "fast" } else { stripped };
    match c.kind {
      SensorKind::Bayer => IMPLEMENTED_BAYER.contains(&original),
      SensorKind::XTrans => IMPLEMENTED_XTRANS.contains(&original),
    }
  });

  out
}

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn every_ported_algo_has_a_mapped_standard_name() {
    for algo in [
      BayerAlgo::Bilinear,
      BayerAlgo::Vng4,
      BayerAlgo::Rcd,
      BayerAlgo::Ahd,
      BayerAlgo::Eahd,
      BayerAlgo::Hphd,
      BayerAlgo::Amaze,
      BayerAlgo::Lmmse,
      BayerAlgo::Igv,
      BayerAlgo::Dcb,
      BayerAlgo::Fast,
      BayerAlgo::AmazeBilinear,
      BayerAlgo::AmazeVng4,
      BayerAlgo::RcdBilinear,
      BayerAlgo::RcdVng4,
      BayerAlgo::DcbBilinear,
      BayerAlgo::DcbVng4,
    ] {
      assert!(algo.standard_name().starts_with("RAWTRP "), "{algo:?} unmapped");
    }

    for algo in [XTransAlgo::OnePass, XTransAlgo::ThreePass, XTransAlgo::Fast, XTransAlgo::TwoPass, XTransAlgo::FourPass] {
      assert!(algo.standard_name().starts_with("RAWTRP "), "{algo:?} unmapped");
    }

    for e in RAWLER_NAMES {
      assert!(e.standard.starts_with("RAWLER "), "{} unmapped", e.original);
    }
  }

  #[test]
  fn candidates_are_rawler_first_then_rawtrp() {
    let c = candidates();
    assert!(c.iter().take(RAWLER_NAMES.len()).all(|x| x.label.starts_with("RAWLER ")));
    assert!(c.iter().skip(RAWLER_NAMES.len()).all(|x| x.label.starts_with("RAWTRP ")));
    // Only ported kernels are advertised, so the count tracks IMPLEMENTED_*, not
    // the full dictionaries.
    assert_eq!(c.len(), RAWLER_NAMES.len() + IMPLEMENTED_BAYER.len() + IMPLEMENTED_XTRANS.len());
    // ids are unique
    let mut ids: Vec<&str> = c.iter().map(|x| x.id).collect();
    ids.sort_unstable();
    let n = ids.len();
    ids.dedup();
    assert_eq!(ids.len(), n, "duplicate candidate id");
  }
}
