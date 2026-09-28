/*
*  fotlab: minimal expat-compatible XML parser (see expat_minimal.h).
*/

#include "expat_minimal.h"

#include <cstdlib>
#include <string>
#include <vector>

struct XML_ParserStruct {
    XML_StartElementHandler startHandler = nullptr;
    XML_EndElementHandler endHandler = nullptr;
    XML_CharacterDataHandler charHandler = nullptr;
    void* userData = nullptr;
    std::string pending;  // accumulated, not-yet-fully-parsed bytes
    size_t pos = 0;       // parse cursor into pending
};

namespace {

std::string decodeEntities(const std::string& in) {
    std::string out;
    out.reserve(in.size());
    size_t i = 0;
    while (i < in.size()) {
        if (in[i] == '&') {
            size_t semi = in.find(';', i);
            if (semi == std::string::npos) {
                out.push_back(in[i]);
                ++i;
                continue;
            }
            std::string ent = in.substr(i + 1, semi - i - 1);
            if (ent == "amp") {
                out.push_back('&');
            } else if (ent == "lt") {
                out.push_back('<');
            } else if (ent == "gt") {
                out.push_back('>');
            } else if (ent == "quot") {
                out.push_back('"');
            } else if (ent == "apos") {
                out.push_back('\'');
            } else if (!ent.empty() && ent[0] == '#') {
                long code = 0;
                if (ent.size() > 1 && (ent[1] == 'x' || ent[1] == 'X')) {
                    code = std::strtol(ent.c_str() + 2, nullptr, 16);
                } else {
                    code = std::strtol(ent.c_str() + 1, nullptr, 10);
                }
                if (code > 0 && code < 0x110000) {
                    if (code < 0x80) {
                        out.push_back(static_cast<char>(code));
                    } else if (code < 0x800) {
                        out.push_back(static_cast<char>(0xC0 | (code >> 6)));
                        out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
                    } else if (code < 0x10000) {
                        out.push_back(static_cast<char>(0xE0 | (code >> 12)));
                        out.push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
                        out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
                    } else {
                        out.push_back(static_cast<char>(0xF0 | (code >> 18)));
                        out.push_back(static_cast<char>(0x80 | ((code >> 12) & 0x3F)));
                        out.push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
                        out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
                    }
                }
            } else {
                out.push_back('&');
                out += ent;
                out.push_back(';');
            }
            i = semi + 1;
        } else {
            out.push_back(in[i]);
            ++i;
        }
    }
    return out;
}

void processTag(XML_ParserStruct* p, const std::string& tag) {
    if (tag.empty()) {
        return;
    }
    // Processing instructions (<?xml ... ?>) and comments (<!-- ... -->): ignore.
    if (tag[0] == '?' || tag[0] == '!') {
        return;
    }

    if (tag[0] == '/') {
        std::string name = tag.substr(1);
        size_t e = name.find_last_not_of(" \t\r\n");
        if (e != std::string::npos) {
            name = name.substr(0, e + 1);
        }
        if (p->endHandler) {
            p->endHandler(p->userData, name.c_str());
        }
        return;
    }

    // Split element name from the attribute list.
    size_t sp = tag.find_first_of(" \t\r\n/");
    std::string name = (sp == std::string::npos) ? tag : tag.substr(0, sp);
    std::string rest = (sp == std::string::npos) ? std::string() : tag.substr(sp);

    bool selfClose = false;
    if (!rest.empty() && rest.back() == '/') {
        selfClose = true;
        rest.pop_back();
    }

    std::vector<std::string> attrs;
    size_t j = 0;
    while (j < rest.size()) {
        while (j < rest.size() &&
               (rest[j] == ' ' || rest[j] == '\t' || rest[j] == '\r' || rest[j] == '\n')) {
            ++j;
        }
        if (j >= rest.size()) {
            break;
        }
        size_t an = rest.find_first_of("= \t\r\n", j);
        if (an == std::string::npos) {
            break;
        }
        std::string aname = rest.substr(j, an - j);
        j = an;
        while (j < rest.size() && rest[j] != '=') {
            ++j;
        }
        if (j >= rest.size()) {
            break;
        }
        ++j;  // skip '='
        while (j < rest.size() &&
               (rest[j] == ' ' || rest[j] == '\t' || rest[j] == '\r' || rest[j] == '\n')) {
            ++j;
        }
        if (j >= rest.size()) {
            break;
        }
        char q = rest[j];
        if (q != '"' && q != '\'') {
            continue;  // malformed attribute; skip
        }
        ++j;
        size_t ve = rest.find(q, j);
        if (ve == std::string::npos) {
            break;
        }
        std::string val = decodeEntities(rest.substr(j, ve - j));
        j = ve + 1;
        attrs.push_back(aname);
        attrs.push_back(val);
    }

    std::vector<const char*> atts;
    for (auto& a : attrs) {
        atts.push_back(a.c_str());
    }
    atts.push_back(nullptr);

    if (p->startHandler) {
        p->startHandler(p->userData, name.c_str(), atts.data());
    }
    if (selfClose && p->endHandler) {
        p->endHandler(p->userData, name.c_str());
    }
}

int parseBuffer(XML_ParserStruct* p, bool isFinal) {
    const std::string& s = p->pending;
    size_t& i = p->pos;
    size_t textStart = i;

    while (i < s.size()) {
        if (s[i] == '<') {
            if (textStart < i) {
                std::string text = decodeEntities(s.substr(textStart, i - textStart));
                if (p->charHandler && !text.empty()) {
                    p->charHandler(p->userData, text.c_str(), static_cast<int>(text.size()));
                }
            }
            size_t tagEnd = s.find('>', i);
            if (tagEnd == std::string::npos) {
                // Incomplete tag: wait for more bytes (text already emitted).
                textStart = i;
                break;
            }
            std::string tag = s.substr(i + 1, tagEnd - i - 1);
            processTag(p, tag);
            i = tagEnd + 1;
            textStart = i;
        } else {
            ++i;
        }
    }

    if (isFinal) {
        if (textStart < i) {
            std::string text = decodeEntities(s.substr(textStart, i - textStart));
            if (p->charHandler && !text.empty()) {
                p->charHandler(p->userData, text.c_str(), static_cast<int>(text.size()));
            }
        }
        p->pending.clear();
        p->pos = 0;
    }
    return XML_STATUS_OK;
}

}  // namespace

XML_Parser XML_ParserCreate(const char* encoding) {
    (void)encoding;
    return new XML_ParserStruct();
}

void XML_SetElementHandler(XML_Parser parser,
                           XML_StartElementHandler start,
                           XML_EndElementHandler end) {
    auto* p = static_cast<XML_ParserStruct*>(parser);
    p->startHandler = start;
    p->endHandler = end;
}

void XML_SetCharacterDataHandler(XML_Parser parser,
                                 XML_CharacterDataHandler handler) {
    auto* p = static_cast<XML_ParserStruct*>(parser);
    p->charHandler = handler;
}

void XML_SetUserData(XML_Parser parser, void* userData) {
    auto* p = static_cast<XML_ParserStruct*>(parser);
    p->userData = userData;
}

int XML_Parse(XML_Parser parser, const char* s, int len, int isFinal) {
    auto* p = static_cast<XML_ParserStruct*>(parser);
    if (s && len > 0) {
        p->pending.append(s, static_cast<size_t>(len));
    }
    return parseBuffer(p, isFinal != 0);
}

void XML_ParserFree(XML_Parser parser) {
    delete static_cast<XML_ParserStruct*>(parser);
}
