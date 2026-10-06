package ru.ravel.rcrifprocessviewer.model.layout

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty


data class DiagramSplit(
	@JacksonXmlProperty(localName = "X")
	val x: Int = 0,
	@JacksonXmlProperty(localName = "Y")
	val y: Int = 0
)