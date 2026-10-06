package ru.ravel.rcrifprocessviewer.model.layout

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement


@JacksonXmlRootElement(localName = "EndPoints")
data class EndPoints(
	@field:JacksonXmlElementWrapper(useWrapping = false)
	@field:JacksonXmlProperty(localName = "DiagramEndPoint")
	val points: List<DiagramEndPoint> = emptyList()
)