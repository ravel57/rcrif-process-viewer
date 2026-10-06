package ru.ravel.rcrifprocessviewer.model.activity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty


@JsonIgnoreProperties(ignoreUnknown = true)
data class ReferredDocument(
	@JacksonXmlProperty(isAttribute = true, localName = "ReferenceName")
	val referenceName: String = "",

	@JacksonXmlProperty(isAttribute = true, localName = "Access")
	val access: String = "",
)
