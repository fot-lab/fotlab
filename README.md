# FotLab

FotLab is an Android APP for digital photography post-processing. It helps user to apply cinematic LUTs
onto RAW images.

## Credits

This project has been inspired by many awesome projects, including:
- [RawTherapee](https://github.com/RawTherapee/RawTherapee)
- [DNGLab](https://github.com/dnglab/dnglab)
- [rawloader](https://github.com/pedrocr/rawloader)
- [LibRaw](https://github.com/LibRaw/LibRaw)
- [RawDB](https://rawdb.dnglab.org/)
- [Raw-Alchemy](https://github.com/shenmintao/Raw-Alchemy)
- [RawAlchemyCpp](https://github.com/GoldJohnKing/RawAlchemyCpp)
- [RapidRAW](https://github.com/CyberTimon/RapidRAW)
- [exiftool](https://exiftool.org/)
- [purple-fringe](https://github.com/mjambon/purple-fringe)
- [docker-android](https://github.com/HQarroum/docker-android)
- [alpine-android](https://github.com/alvr/alpine-android)

## License

The original code of this project is licensed under the GNU General Public License v3.0 (GPL-3.0). See [LICENSE.md](LICENSE.md) for the full notice.

Third-party modules under `external/` (git submodules) are independent external projects, each governed by its own license. This project claims no rights over them and provides no warranty of any kind regarding the accuracy, completeness, or applicability of their license terms. When using, modifying, or redistributing those modules, refer to the license of each individual module.

## Features
- [x] RAW image decoding to linear space (from dnglab/rawler)
- [ ] JPEG/PNG/HEIC image decoding to linear space
- [x] Working space D50 ProPhoto (dnglab/rawler/RawAlchemyCpp)
- [ ] Camera profile decoding + correction
- [ ] Lens profile decoding + correction
- [x] Exposure adjustment
- [x] Highlight compression
- [x] Defringe-correction for transitional chromatic aberration
- [x] Defringe-correction for axial chromatic aberration purple/magenta (heuristic model with 0.00000001 B parameters from purple-fringe)
- [x] Defringe-correction for axial chromatic aberration green/cyan (inspired by purple-fringe)
- [ ] Crop and rotate
- [x] White balance adjustment
- [x] Demosaic alogrithm (from RawTherapee)
- [x] Denoise alogrithm (implemented)
- [ ] Dehaze alogrithm 
- [x] Applying gamma/LOG curves (implemented by RawAlchemyCpp)
- [x] Applying LUT filters (implemented by from RawAlchemyCpp)
- [x] Image export

