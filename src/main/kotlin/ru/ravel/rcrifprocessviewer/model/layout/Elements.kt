package ru.ravel.rcrifprocessviewer.model.layout

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty


data class Elements(
	@JacksonXmlElementWrapper(useWrapping = false)
	@JacksonXmlProperty(localName = "DiagramElement")
	val diagramElements: List<DiagramElement> = emptyList()
)