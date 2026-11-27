# External study — Picture Transfer Protocol (ISO 15740): the PTP this repository actually implements

- ID: FOTLAB-SURVEY-000002
- Status: Draft
- Priority: P2
- Created: 2026-11-27
- Owner: —
- Related: [`FOTLAB-SURVEY-000001`](FOTLAB-SURVEY-000001.md) (IEEE 1588 Precision Time Protocol — a different standard), [`FOTLAB-FEATUR-000001`](../../DESIGN/detail/FOTLAB-FEATUR-000001.md) (camera PTP/IP feature), [`FOTLAB-DATABS-000004`](../../DESIGN/detail/FOTLAB-DATABS-000004.md) (transfer queue object identity)
- Source: HandWiki, "Picture Transfer Protocol" (page supplied 2026-11-27; the page's infobox links to <https://www.iso.org/standard/63602.html>). Reproduced here as an external reference study. It records no decision or requirement for this repository.

> **Terminology — which "PTP"?** This document is about **Picture Transfer Protocol (ISO 15740)**.
> That is what **PTP** means everywhere in this repository — the camera file-transfer protocol in
> `app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/`. It is **not** Precision Time
> Protocol (IEEE 1588), the clock-synchronisation standard studied in
> [`FOTLAB-SURVEY-000001`](FOTLAB-SURVEY-000001.md).

## Background & Goal

The camera module implements PTP — picture transfer. This document keeps an external description
of that standard on file so that "PTP" in a design note, a `ptp://` URI or a class name can be
traced to the actual protocol and its capabilities, and so it cannot be confused with the
unrelated IEEE 1588 timing standard. Nothing here changes the camera module; `FOTLAB-SURVEY-000001`
is the companion study of the standard we do **not** implement.

## Source text — HandWiki, "Picture Transfer Protocol"

### At a glance

| Field | Value |
| --- | --- |
| Status | Published |
| Year started | 2000; 26 years ago |
| Latest version | ISO 15740:2013; 2013; 13 years ago |
| Organization | I3A, ISO |
| Committee | IT10, ISO/TC 42 |
| Authors | Tim Looney and Tim Whitcher (Eastman Kodak Company), Eran Steinberg (Fotonation), and others |
| Related standards | PIMA 15470 |
| Domain | Digital photography, Network protocols |
| Abbreviation | PTP |
| Website | www.iso.org/standard/63602.html |
| Short description | Protocol to transfer images from digital cameras to computers |

Picture Transfer Protocol (PTP) is a protocol originally developed by the Photographic and Imaging
Manufacturers Association (PIMA) (later known as the International Imaging Industry Association)
to allow the transfer of images from digital cameras to computers and other peripheral devices
without the need for additional device drivers. The protocol was standardized by the ISO in 2005.
The current version of the standard is ISO 15740:2013.[1]

It is further standardized for USB by the USB Implementers Forum as the still image capture device
class. USB is the default network transport media for PTP devices. USB PTP is a common alternative
to the USB mass-storage device class (USB MSC), as a digital camera connection protocol. Some
cameras support both modes.

### Contents

1. Description
2. History
3. Storage
4. Device control
5. Extensions
6. Operating system support
7. Version 1.11
8. Drawbacks
9. See also
10. References
11. External links

### Description

PTP specifies a way of creating, transferring and manipulating objects which are typically
photographic images such as a JPEG file. While it is common to think of the objects that PTP handle
as files, they are abstract entities identified solely by a 32-bit object ID. These objects can
however have parents and siblings so that a file-system–like view of device contents can be
created.

### History

Until the standardization of PTP, digital camera vendors used different proprietary protocols for
controlling digital cameras and transferring images to computers and other host devices. In an
earlier but unrelated project, the term "Picture Transfer Protocol" and the acronym "PTP" were both
coined by Steve Mann, summarizing work on the creation of a Linux-friendly way of transferring
pictures to and from home-made wearable computers[2] at a time when most cameras required the use
of Microsoft Windows or Mac OS device drivers to transfer their pictures to a computer.

PTP was originally standardized as PIMA 15470 in 2000, while it was developed by the IT10 committee.
Key contributors to the standard included Tim Looney and Tim Whitcher (Eastman Kodak Company) and
Eran Steinberg (Fotonation).[3]

### Storage

PTP does not specify a way for objects to be stored – it is a communication protocol. Nor does it
specify a transport layer. However, it is designed to support existing standards, such as Exif,
TIFF/EP, DCF, and DPOF, and is commonly implemented over the USB and FireWire transport layers.

Images on digital cameras are generally stored as files on a mass storage device, such as a memory
card, which is formatted with a file system, most commonly FAT12, FAT16 or FAT32, which may be laid
out as per the Design rule for Camera File system (DCF) specification. But none of these are
required as PTP abstracts from the underlying representation.

By contrast, if a camera is mounted via USB MSC, the physical file system and layout are exposed to
the user.

### Device control

Many modern digital cameras from Canon and Nikon can be controlled via PTP from a USB host enabled
computing device (smartphone, PC or Arduino for example). As is the norm for PTP, the communication
takes place over a USB connection. When interacting with the camera in this manner, it is expected
that the USB endpoints are in (synchronous) Bulk Transfer Mode, for getting/setting virtually all
the camera's features/properties (such as ISO, Aperture, Shutter speed and focus). Events raised by
the camera, in response to specific actions performed by the host device, are sent back to the host
via the USB asynchronous Interrupt endpoint.

In addition to changing the camera's settings and operating mode, it is possible to receive a
through-the-lens view using "Live View". As described above, the storage objects of the camera's
memory cards can be manipulated too.

By controlling a camera in this way it is possible to augment its capabilities. For example, if the
controlling software was running on a smartphone with GPS functionality, it would be possible to
add the GPS coordinates to an image's Exif data, at the time of image capture — even if the camera
itself had no GPS functionality.

### Extensions

A number of protocols have been developed that extend PTP. PTP/IP,[4] developed by FotoNation and
first implemented in a round of Wi-Fi digital cameras by Nikon, Canon, and Eastman Kodak, allows
data transfer over any IP-based network. A vendor extension registry for PTP is maintained by the
Society for Imaging Science and Engineering (IS&T).

Media Transfer Protocol (MTP), developed by Microsoft, allows for transfer over wireless or wired
networks based in part on FotoNation's PTP/IP, but also allows users to transfer other media aside
from pictures, as well as for tagging objects with extended metadata (such as title, artist and
similar metadata).

### Operating system support

Microsoft Windows has supported PTP from Windows ME onwards (excluding Windows CE). Microsoft
implements PTP on Windows through Windows Image Acquisition.[5][6] A disadvantage of PTP on Windows
as compared to USB mass storage is that Windows does not assign drive letters to PTP devices, so
image files on them cannot be manipulated by scripts or standard Windows programs, only by Windows
Explorer or applications with specially written PTP support. Also, Windows Explorer does not display
file modification timestamps (though these are available via the file Properties popup).

PTP on Linux and other free and open-source operating systems is supported by a number of libraries,
such as libgphoto and libptp, used by applications such as digiKam and F-Spot. As on Microsoft
Windows there is no native support on Linux, but by means of GVfs the devices can easily be mounted
and made available to applications that use standard POSIX commands and library functions.

Android supports PTP, so that software programs that support grabbing photos from a digital camera
will support grabbing photos from an Android phone when you select the PTP mode.[7] As with MTP, a
limitation is that when transferring photos from a computer to the Android device, file timestamps
are replaced with the time of the copy. For copies from the Android device to a computer however,
the timestamps are preserved.

On macOS, PTP is supported natively through the Image Capture app,[8] which has been available
starting with Mac OS X 10.0. On iOS, the first-party Photos app has enabled transfer from PTP devices
starting with the release of the iPad Camera Connection Kit in 2010, and APIs giving PTP
functionality to 3rd party developers are available starting iOS 17.[9]

### Version 1.11

PTP v1.11 (ISO 15740:2013) is a minor revision to PTP v1.1 (ISO 15740:2008). Both the 2008 and 2013
versions of PTP are fully backward-compatible with PTP v1.0 (ISO 15740:2005), and offer optional
performance, compatibility, and feature enhancements including:

- A mechanism for handling streaming content
- A mechanism to support multiple vendor extension sets
- Support for objects larger than the 4GiB size limit set by PTP v1.0, by requiring 64 bits (8 bytes)
  for object size
- Support for retrieval of ObjectHandles in enumerated chunks. This may reduce long response times
  for some devices that possess large numbers of objects
- Support for arbitrary resizing prior to image transmission (responder scaling). In PTP v1.0, image
  sizes might be requested in full-resolution or thumbnail size only
- Support for arrays of datasets. This can be used to reduce the number of required transactions
  necessary for device characterization from being a function of the number of objects on the device
  down to one
- A fast file characterization operation that exploits dataset arrays to request, in a single
  transaction, only the minimum data required to characterize a typical filesystem
- A new standard ObjectFormatCode to support the Digital Negative (DNG) file format

### Drawbacks

- Renaming file objects directly is not possible without copying or rewriting them
- Modification of file contents is not supported (the file needs to be re-transferred completely)
- Some drawbacks are OS-specific — see § Operating system support

### See also

- Design rule for Camera File system
- PictBridge

### References

1. "ISO 15740:2013" (in en).
2. Steve Mann (1999-02-01). "University of Toronto WearComp Linux Project, part 1 of a 2-part series". Linux Journal (58): 5. Retrieved 2013-12-28.
3. "PTP Standards" (in en).
4. Bigioi, P.; Susanu, G.; Steinberg, E.; Corcoran, P. (2005). "PTP/IP - a new transport specification for wireless photography". IEEE Transactions on Consumer Electronics 51 (1): 240–244. doi:10.1109/TCE.2005.1405726. Bibcode: 2005ITCE...51..240B.
5. "About Windows Image Acquisition". MSDN. Microsoft. Retrieved 2009-01-05.
6. "Still image connectivity for Windows". Microsoft. December 4, 2001. Retrieved 2009-01-05.
7. "Learn Everything's - USB Connections: MTP, PTP, and USB Mass Storage" (in en-US).
8. "Transfer images in Image Capture on Mac". Apple. Retrieved 2025-06-21.
9. "AVExternalStorageDevice". Apple. Retrieved 2025-06-21.

### External links

- Official website
- PTP transport over USB specifications, USB.org.
- Microsoft and FotoNation Team to Support the Media Transfer Protocol For Wireless Digital Still Cameras, Microsoft News Center.
- Free software with PTP support

## Relevance to this repository

The points below map the source text to the code this repository actually ships; they are the
reason this study is filed alongside the camera work and not as a generic protocol note.

- **Object identity** — the source describes PTP objects as abstract entities with a 32-bit object
  ID and a parent/sibling structure. That is the `PtpObject` handle model in
  `app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/model/`, and why
  `FOTLAB-DATABS-000004` R5 stores the folder and file name beside the handle: the handle is
  device-local, not a portable file path.
- **Bulk endpoints and events** — the source's "Device control" section describes command/data over
  synchronous USB bulk endpoints and events over the asynchronous interrupt endpoint.
  `app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/transport/UsbPtpTransport.kt` follows
  that split. The camera-settings and live-view capabilities described in that section are
  deliberately **out of scope** here (`FOTLAB-FEATUR-000001` R3).
- **PTP/IP** — the "Extensions" section is the basis for
  `app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/transport/IpPtpTransport.kt` and
  `app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/transport/IpHandshake.kt`, discovered
  via `_ptp._tcp.local.` mDNS. MTP is a related but different standard; no MTP code is present in
  `app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/` as documented in
  `app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/ARCHITECTURE.md`.
- **Version 1.11 features in use** — 64-bit object size
  (`app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/operation/ObjectInfoDecoder.kt`) and
  `GetObjectHandles` in bounded batches
  (`app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/operation/PtpFileOperations.kt`)
  correspond to two of the 2013 enhancements listed above. Streaming content, responder scaling,
  dataset arrays and the DNG `ObjectFormatCode` are outside the operation set described in
  `app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/ARCHITECTURE.md`.
- **Not applicable** — PTP says nothing about object storage or the transport layer; those are
  decisions of this repository. No rule from the IEEE 1588 study (`FOTLAB-SURVEY-000001`) transfers
  to this protocol.

## Change History

- 2026-11-27 — Created from the HandWiki "Picture Transfer Protocol" page supplied by the owner.
  Records the external description of ISO 15740 PTP (object identity, storage and device control,
  PTP/IP and MTP extensions, OS support, v1.11 enhancements, drawbacks) and maps it to the camera
  module. Keeps this repository's "PTP" unambiguous: picture transfer, not IEEE 1588 precision
  time. Documentation only; no requirement changed.
