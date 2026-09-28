/*
*  fotlab: self-contained minimal replacement for the subset of the expat C API
*  that RawTherapee's lcp.cc uses. No external XML library is required.
*
*  Only the following are implemented:
*    XML_ParserCreate, XML_SetElementHandler, XML_SetCharacterDataHandler,
*    XML_SetUserData, XML_Parse, XML_ParserFree
*  plus the handler typedefs and the XMLCALL / XML_Char / XML_Status macros.
*
*  The parser is a small, defensive XML reader. It is *not* a validating or
*  fully general XML processor — it only needs to drive RawTherapee's LCP SAX
*  handlers (start element with attributes, end element, character data), which
*  tolerate unknown tags and only act on a small fixed set of element names.
*/

#ifndef EXPAT_MINIMAL_H
#define EXPAT_MINIMAL_H

#include <cstddef>

#define XMLCALL
#define XML_STATIC

typedef char XML_Char;

enum XML_Status {
    XML_STATUS_ERROR = 0,
    XML_STATUS_OK = 1,
    XML_STATUS_SUSPENDED = 2
};

struct XML_ParserStruct;
typedef struct XML_ParserStruct* XML_Parser;

typedef void (XMLCALL *XML_StartElementHandler)(void* userData,
                                                 const char* name,
                                                 const char** atts);
typedef void (XMLCALL *XML_EndElementHandler)(void* userData, const char* name);
typedef void (XMLCALL *XML_CharacterDataHandler)(void* userData,
                                                  const XML_Char* s,
                                                  int len);

XML_Parser XML_ParserCreate(const char* encoding);
void XML_SetElementHandler(XML_Parser parser,
                            XML_StartElementHandler start,
                            XML_EndElementHandler end);
void XML_SetCharacterDataHandler(XML_Parser parser,
                                 XML_CharacterDataHandler handler);
void XML_SetUserData(XML_Parser parser, void* userData);
int XML_Parse(XML_Parser parser, const char* s, int len, int isFinal);
void XML_ParserFree(XML_Parser parser);

#endif  // EXPAT_MINIMAL_H
