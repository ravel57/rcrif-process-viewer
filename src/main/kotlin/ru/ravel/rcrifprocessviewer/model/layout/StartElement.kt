package ru.ravel.rcrifprocessviewer.model.layout

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
data class StartElement(
	@JacksonXmlProperty(isAttribute = true, localName = "UID")
	val uid: String? = null,

	@JacksonXmlProperty(localName = "X")
	val x: Int? = null,

	@JacksonXmlProperty(localName = "Y")
	val y: Int? = null,

	@JacksonXmlProperty(localName = "Width")
	val width: Int? = null,

	@JacksonXmlProperty(localName = "Height")
	val height: Int? = null,

	@JacksonXmlProperty(localName = "OutConnectionRefs")
	val outConnectionRefs: RefList? = RefList(),

	@JacksonXmlProperty(localName = "InConnectionRefs")
	val inConnectionRefs: RefList? = RefList(),
)