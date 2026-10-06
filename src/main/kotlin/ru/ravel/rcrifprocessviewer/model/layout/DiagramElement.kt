package ru.ravel.rcrifprocessviewer.model.layout

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.dataformat.xml.annotation.*

@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class DiagramElement(
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

	@JacksonXmlProperty(localName = "Reference")
	val reference: String? = null,

	@JacksonXmlProperty(localName = "Comment")
	val comment: Comment? = null,

	@JacksonXmlProperty(localName = "OutConnectionRefs")
	@JsonInclude(JsonInclude.Include.ALWAYS)
	val outConnectionRefs: RefList? = null,

	@JacksonXmlProperty(localName = "InConnectionRefs")
	@JsonInclude(JsonInclude.Include.ALWAYS)
	val inConnectionRefs: RefList? = null,
)