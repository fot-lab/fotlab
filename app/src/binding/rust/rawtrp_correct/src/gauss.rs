//! Separable Gaussian blur — supports `CA_correct_RT`'s `avoidColourshift`
//! post-step, which blurs the per-pixel R/B correction factors (RT's own
//! `gaussianBlur`, `rtengine/gauss.h`, uses a small fixed-kernel recursive
//! filter; this is a straightforward separable convolution with the same
//! `sigma` so the factor smoothing is equivalent).

/// Blur `src` (`w x h`, row-major) into `dst` with a separable Gaussian of the
/// given `sigma` (standard deviation, in pixels of the source grid). Edge pixels
/// use clamp-to-edge. `src` and `dst` may alias (a temporary is used anyway).
pub fn gaussian_blur(src: &[f32], dst: &mut [f32], w: usize, h: usize, sigma: f64) {
    if w == 0 || h == 0 {
        return;
    }
    if sigma <= 0.0 {
        dst.copy_from_slice(src);
        return;
    }

    let radius = (3.0 * sigma).ceil() as usize;
    let mut kernel = vec![0.0f64; 2 * radius + 1];
    let mut sum = 0.0;
    for (i, k) in kernel.iter_mut().enumerate() {
        let x = i as f64 - radius as f64;
        let v = (-0.5 * (x / sigma) * (x / sigma)).exp();
        *k = v;
        sum += v;
    }
    for k in &mut kernel {
        *k /= sum;
    }

    let mut tmp = vec![0.0f32; w * h];

    // horizontal pass
    for y in 0..h {
        for x in 0..w {
            let mut acc = 0.0f64;
            for (ki, kv) in kernel.iter().enumerate() {
                let xx = (x as isize + ki as isize - radius as isize)
                    .clamp(0, w as isize - 1) as usize;
                acc += src[y * w + xx] as f64 * kv;
            }
            tmp[y * w + x] = acc as f32;
        }
    }

    // vertical pass
    for y in 0..h {
        for x in 0..w {
            let mut acc = 0.0f64;
            for (ki, kv) in kernel.iter().enumerate() {
                let yy = (y as isize + ki as isize - radius as isize)
                    .clamp(0, h as isize - 1) as usize;
                acc += tmp[yy * w + x] as f64 * kv;
            }
            dst[y * w + x] = acc as f32;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::gaussian_blur;

    #[test]
    fn blur_preserves_flat() {
        let w = 16;
        let h = 16;
        let src: Vec<f32> = vec![0.5; w * h];
        let mut dst = vec![0.0; w * h];
        gaussian_blur(&src, &mut dst, w, h, 5.0);
        for v in &dst {
            assert!((v - 0.5).abs() < 1e-5);
        }
    }

    #[test]
    fn blur_smooths_impulse() {
        let w = 32;
        let h = 32;
        let mut src = vec![0.0f32; w * h];
        src[h / 2 * w + w / 2] = 1.0;
        let mut dst = vec![0.0; w * h];
        gaussian_blur(&src, &mut dst, w, h, 4.0);
        // peak should be reduced and spread
        let peak = dst[h / 2 * w + w / 2];
        assert!(peak < 1.0 && peak > 0.0);
    }
}
