//! Gaussian elimination with partial pivoting — port of RT's `LinEqSolve`
//! (`CA_correct_RT.cc`), used by the pass-1 auto-fit 2-D polynomial regression
//! to solve the normal-equations matrix for the residual-CA polynomial
//! coefficients.

/// Solve `A x = b` for an `n`-by-`n` system, in place.
///
/// `a` is the row-major coefficient matrix (`n*n` entries, mutated into
/// triangular form), `b` the right-hand side (mutated during triangulation),
/// and `solution` receives the answer. Mirrors RawTherapee's `LinEqSolve`
/// (`CA_correct_RT.cc`) exactly: partial pivoting, row swaps of `a` and `b`,
/// and a `false` return on a (near-)singular pivot. The upstream routine uses
/// `float`; this port uses `f64` to match the `double` polynomial coefficients
/// it feeds.
pub fn lin_eq_solve(n: usize, a: &mut [f64], b: &mut [f64], solution: &mut [f64]) -> bool {
    for k in 0..n - 1 {
        // partial pivot
        let mut p = k;
        let mut max = a[k * n + k].abs();
        for i in (k + 1)..n {
            if a[i * n + k].abs() > max {
                max = a[i * n + k].abs();
                p = i;
            }
        }
        if p != k {
            for i in k..n {
                a.swap(k * n + i, p * n + i);
            }
            b.swap(k, p);
        }
        let pivot = a[k * n + k];
        if pivot == 0.0 {
            return false;
        }
        for i in (k + 1)..n {
            let v = a[i * n + k] / pivot;
            if v != 0.0 {
                for j in k..n {
                    a[i * n + j] -= v * a[k * n + j];
                }
                b[i] -= v * b[k];
            }
        }
    }
    for k in (0..n).rev() {
        let mut sum = b[k];
        for i in (k + 1)..n {
            sum -= a[k * n + i] * solution[i];
        }
        if a[k * n + k] == 0.0 {
            return false;
        }
        solution[k] = sum / a[k * n + k];
    }
    true
}

#[cfg(test)]
mod tests {
    use super::lin_eq_solve;

    #[test]
    fn solves_identity() {
        let mut a = [0.0f64; 81];
        let mut b = [0.0f64; 9];
        let mut sol = [0.0f64; 9];
        for i in 0..9 {
            a[i * 9 + i] = 1.0;
            b[i] = (i + 1) as f64;
        }
        assert!(lin_eq_solve(9, &mut a, &mut b, &mut sol));
        for i in 0..9 {
            assert!((sol[i] - (i + 1) as f64).abs() < 1e-12);
        }
    }

    #[test]
    fn solves_2x2() {
        // [[2,1],[1,3]] x = [5,10] -> x = [1,3]
        let mut a = [0.0f64; 4];
        let mut b = [0.0f64; 2];
        let mut sol = [0.0f64; 2];
        a[0] = 2.0; a[1] = 1.0; a[2] = 1.0; a[3] = 3.0;
        b[0] = 5.0; b[1] = 10.0;
        assert!(lin_eq_solve(2, &mut a, &mut b, &mut sol));
        assert!((sol[0] - 1.0).abs() < 1e-12);
        assert!((sol[1] - 3.0).abs() < 1e-12);
    }

    #[test]
    fn rejects_singular() {
        let mut a = [0.0f64; 4];
        let mut b = [1.0f64; 2];
        let mut sol = [0.0f64; 2];
        a[0] = 0.0; a[3] = 1.0; // singular first pivot
        assert!(!lin_eq_solve(2, &mut a, &mut b, &mut sol));
    }
}
