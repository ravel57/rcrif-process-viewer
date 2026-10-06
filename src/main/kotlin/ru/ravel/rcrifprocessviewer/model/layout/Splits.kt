package ru.ravel.rcrifprocessviewer.model.layout

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement


@JacksonXmlRootElement(localName = "Splits")
data class Splits(
	@field:JacksonXmlElementWrapper(useWrapping = false)
	@field:JacksonXmlProperty(localName = "DiagramSplit")
	val splits: List<DiagramSplit> = emptyList()
)