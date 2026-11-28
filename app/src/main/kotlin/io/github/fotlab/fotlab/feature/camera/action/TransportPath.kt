package io.github.fotlab.fotlab.feature.camera.action

/**
 * 4-segment path encoding — every value uniquely identifies the exact
 * wire protocol the session is using. Format:
 *
 *     <transport>_<link>_<vendor>_<segment>
 *
 * where:
 *   transport = PTP (ISO 15740 binary container) | HTTP (JSON-RPC / REST)
 *   link      = USB (bulk endpoints) | IP (TCP/UDP)
 *   vendor    = STD (generic ISO fallback) | SONY | CANON | NIKON | FUJIFILM
 *   segment   = the opcode block or API suffix that actually runs
 *
 * Rule: [STD] paths mean "no vendor extension — run ISO standard only".
 * Vendor-specific segments only appear when the device genuinely uses
 * them. If a Sony camera is connected and ptpStdOnly=true, it routes to
 * PTP_USB_STD_ISO / PTP_IP_STD_ISO instead of PTP_USB_SONY_0X92XX.
 *
 * Adding a new variant = add one enum value here. [CameraActionContext],
 * [PtpExecutor] and every action routine read [path] and dispatch from
 * its declarative properties — they never hard-code a brand branch.
 */
enum class TransportPath(
    val transport: Transport,
    val link: Link,
    val vendor: Vendor,
    val segment: Segment,
) {
    // ── ISO standard paths (no vendor extension) ────────────────────
    PTP_USB_STD_ISO(Transport.PTP, Link.USB, Vendor.STD, Segment.ISO),
    PTP_IP_STD_ISO( Transport.PTP, Link.IP,  Vendor.STD, Segment.ISO),

    // ── Sony ─────────────────────────────────────────────────────────
    PTP_USB_SONY_0X92XX(     Transport.PTP, Link.USB, Vendor.SONY,   Segment.SONY_0X92XX),
    HTTP_IP_SONY_NEX(        Transport.HTTP, Link.IP,  Vendor.SONY,   Segment.NEX),

    // ── Canon — 0x91xx works on both USB and PTP/IP ───────────────────
    PTP_USB_CANON_0X91XX(Transport.PTP, Link.USB, Vendor.CANON, Segment.CANON_0X91XX),
    PTP_IP_CANON_0X91XX( Transport.PTP, Link.IP,  Vendor.CANON, Segment.CANON_0X91XX),

    // ── Nikon — 0x90xx on both transports ─────────────────────────────
    PTP_USB_NIKON_0X90XX(Transport.PTP, Link.USB, Vendor.NIKON, Segment.NIKON_0X90XX),
    PTP_IP_NIKON_0X90XX( Transport.PTP, Link.IP,  Vendor.NIKON, Segment.NIKON_0X90XX),

    // ── Fuji — USB only, 0xD2xx / 0xDFxx block ────────────────────────
    PTP_USB_FUJI_0XD2XX(Transport.PTP, Link.USB, Vendor.FUJIFILM, Segment.FUJI_0XD2XX),
    ;

    // ── Predicates for natural dispatch branches ─────────────────────

    val isPtp:   Boolean get() = transport == Transport.PTP
    val isHttp:  Boolean get() = transport == Transport.HTTP
    val isUsb:   Boolean get() = link == Link.USB
    val isIp:    Boolean get() = link == Link.IP

    /** The segment is a vendor extension — standard-first should skip ISO
     *  and go straight to the vendor block (e.g. Sony 0x92xx on USB). */
    val isVendorSegment: Boolean get() = segment != Segment.ISO

    /** ptpStdOnly flag forces the ISO standard path regardless of enum value. */
    fun effectiveSegment(ptpStdOnly: Boolean): Segment =
        if (isPtp && ptpStdOnly) Segment.ISO else segment
}

enum class Transport { PTP, HTTP }
enum class Link     { USB, IP }
enum class Vendor   { STD, SONY, CANON, NIKON, FUJIFILM }
enum class Segment  {
    ISO,            // Generic ISO 15740 standard commands
    SONY_0X92XX,    // Sony USB vendor block (0x9202-0x9250)
    NEX,            // Sony NEX HTTP ScalarWebAPI (JSON-RPC 2.0 over POST)
    CANON_0X91XX,   // Canon vendor block (0x9101-0x917A), works on USB + IP
    NIKON_0X90XX,   // Nikon vendor block (0x90C4-0x9431), works on USB + IP
    FUJI_0XD2XX,    // Fuji USB vendor block (0xD212-0xDF01)
}
