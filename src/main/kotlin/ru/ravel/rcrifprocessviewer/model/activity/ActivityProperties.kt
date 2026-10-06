package ru.ravel.rcrifprocessviewer.model.activity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty

/**
 * Универсальная модель Properties.xml любой активности.
 *
 * Корневой элемент у разных типов активностей разный
 * (FormActivityDefinition, BizRuleActivityDefinition, ...), но нас интересуют только
 * общие для всех части: ReferenceName и список дата-документов.
 * Jackson XML игнорирует имя корневого элемента при десериализации,
 * поэтому одной модели достаточно для всех типов.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ActivityProperties(
	@JacksonXmlProperty(isAttribute = true, localName = "ReferenceName")
	val referenceName: String? = null,

	@JacksonXmlProperty(localName = "ReferredDocuments")
	val referredDocuments: ReferredDocuments? = null,
)
