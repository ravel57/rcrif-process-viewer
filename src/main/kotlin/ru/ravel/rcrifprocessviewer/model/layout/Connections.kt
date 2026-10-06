package ru.ravel.rcrifprocessviewer.model.layout

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty


@JsonInclude(JsonInclude.Include.NON_EMPTY)
data class Connections(
	@JacksonXmlElementWrapper(useWrapping = false)
	@JacksonXmlProperty(localName = "DiagramConnection")
	val diagramConnections: MutableList<DiagramConnection> = mutableListOf()
)