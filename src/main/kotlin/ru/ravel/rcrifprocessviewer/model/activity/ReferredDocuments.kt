package ru.ravel.rcrifprocessviewer.model.activity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty


@JsonIgnoreProperties(ignoreUnknown = true)
data class ReferredDocuments(
	@JacksonXmlElementWrapper(useWrapping = false)
	@JacksonXmlProperty(localName = "ReferredDocument")
	val documents: List<ReferredDocument> = emptyList(),
)
