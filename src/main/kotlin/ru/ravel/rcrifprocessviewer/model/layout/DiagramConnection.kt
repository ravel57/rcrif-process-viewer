package ru.ravel.rcrifprocessviewer.model.layout

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.fasterxml.jackson.dataformat.xml.annotation.*


@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class DiagramConnection(
	@JacksonXmlProperty(isAttribute = true, localName = "UID")
	val uid: String? = null,

	@JacksonXmlProperty(localName = "Splits")
	@JsonSetter(nulls = Nulls.SKIP)
	val splits: Splits? = null,

	@JacksonXmlProperty(localName = "EndPoints")
	val endPoints: EndPoints? = null
)