//! Gaussian elimination with partial pivoting — port of RT's `LinEqSolve`
//! (`CA_correct_RT.cc`), used by the pass-1 auto-fit 2-D polynomial regression
//! to solve the normal-equations matrix for the residual-CA polynomial
//! coefficients.

/// Solve `a x = b` in place for a `9x9` system (`a` row-major, `b` length 9).
///
/// Mirrors the upstream `LinEqSolve(float (*a)[9], float *b)` exactly: partial
/// pivoting, row swaps of both `a` and `b`, and a `false` return on a
/// (near-)singular pivot. The upstream routine uses `float`; this port uses
/// `f64` to match the `double` polynomial coefficients it feeds.
pub fn lin_eq_solve(a: &mut [[f64; 9]; 9], b: &mut [f64; 9]) -> bool {
    let n = 9;
    for k in 0..n - 1 {
        // partial pivot
        let mut p = k;
        let mut max = a[k][k].abs();
        for i in (k + 1)..n {
            if a[i][k].abs() > max {
                max = a[i][k].abs();
                p = i;
            }
        }
        if p != k {
            a.swap(k, p);
            b.swap(k, p);
        }
        let pivot = a[k][k];
        if pivot == 0.0 {
            return false;
        }
        for i in (k + 1)..n {
            let v = a[i][k] / pivot;
            if v != 0.0 {
                for j in k..n {
                    a[i][j] -= v * a[k][j];
                }
                b[i] -= v * b[k];
            }
        }
    }
    for i in (0..n).rev() {
        let mut sum = b[i];
        for j in (i + 1)..n {
            sum -= a[i][j] * b[j];
        }
        if a[i][i] == 0.0 {
            return false;
        }
        b[i] = sum / a[i][i];
    }
    true
}

#[cfg(test)]
mod tests {
    use super::lin_eq_solve;

    #[test]
    fn solves_identity() {
        let mut a = [[0.0; 9]; 9];
        let mut b = [0.0f64; 9];
        for i in 0..9 {
            a[i][i] = 1.0;
            b[i] = (i + 1) as f64;
        }
        assert!(lin_eq_solve(&mut a, &mut b));
        for i in 0..9 {
            assert!((b[i] - (i + 1) as f64).abs() < 1e-12);
        }
    }

    #[test]
    fn solves_small_system() {
        // 3x3 embedded in 9x9: [[2,1],[1,3]] x = [5,10] -> x = [1,3]
        let mut a = [[0.0; 9]; 9];
        let mut b = [0.0f64; 9];
        a[0][0] = 2.0; a[0][1] = 1.0;
        a[1][0] = 1.0; a[1][1] = 3.0;
        b[0] = 5.0; b[1] = 10.0;
        assert!(lin_eq_solve(&mut a, &mut b));
        assert!((b[0] - 1.0).abs() < 1e-12);
        assert!((b[1] - 3.0).abs() < 1e-12);
    }

    #[test]
    fn rejects_singular() {
        let mut a = [[0.0; 9]; 9];
        let mut b = [1.0f64; 9];
        a[0][0] = 0.0; // singular pivot on first row
        a[1][1] = 1.0;
        assert!(!lin_eq_solve(&mut a, &mut b));
    }
}
