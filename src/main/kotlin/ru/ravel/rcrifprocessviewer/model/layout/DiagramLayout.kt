package ru.ravel.rcrifprocessviewer.model.layout

import com.fasterxml.jackson.annotation.*
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import javax.xml.XMLConstants

@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
@JacksonXmlRootElement(localName = "DiagramLayout")
data class DiagramLayout(

	@JacksonXmlProperty(isAttribute = true, localName = "xsi", namespace = XMLConstants.XMLNS_ATTRIBUTE_NS_URI)
	val xmlnsXsi: String? = "http://www.w3.org/2001/XMLSchema-instance",

	@JacksonXmlProperty(isAttribute = true, localName = "xsd", namespace = XMLConstants.XMLNS_ATTRIBUTE_NS_URI)
	val xmlnsXsd: String? = "http://www.w3.org/2001/XMLSchema",

	@JacksonXmlProperty(isAttribute = true, localName = "UID")
	val uid: String? = null,

	@JacksonXmlProperty(localName = "Elements")
	val elements: Elements? = null,

	@JacksonXmlProperty(localName = "Connections")
	val connections: Connections? = null,

	@JacksonXmlProperty(localName = "StartElement")
	val startElement: StartElement? = null,

	@JacksonXmlProperty(localName = "Exits")
	val exits: Exits? = null,
)