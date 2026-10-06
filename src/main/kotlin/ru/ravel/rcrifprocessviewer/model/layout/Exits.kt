package ru.ravel.rcrifprocessviewer.model.layout

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty


@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
data class Exits(
	@JacksonXmlElementWrapper(useWrapping = false)
	@JacksonXmlProperty(localName = "string")
	val values: List<String> = emptyList()
)