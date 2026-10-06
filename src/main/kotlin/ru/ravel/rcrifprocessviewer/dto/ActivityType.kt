package ru.ravel.rcrifprocessviewer.dto

enum class ActivityType(val prefix: String, val exits: List<String>) {
	FORM("FM", mutableListOf()),
	BIZ_RULE("BR", listOf("True", "False", "Failed")),
	SEGMENTATION_TREE("ST", mutableListOf("AllFalse", "Failed")),
	DATA_SOURCE("DS", listOf("Empty", "Completed", "Unavailable", "Timeout", "Failed")),
	DATA_MAPPING("DM", listOf("Completed", "Failed")),
	SET_VALUE("SV", listOf("Completed", "Failed")),
	PROCEDURE_CALL("PC", mutableListOf("Completed")),
	DISPATCH("DR", listOf("Completed", "Unassigned", "Failed")),
	WAIT("WA", listOf("Updated", "UserUnfreeze", "ExitTimeout")),
	PROCEDURE_RETURN("PR", listOf()),
	END_PROCEDURE("EP", listOf()),
	SEND_EMAIL("SE", listOf("Completed", "Failed")),
	SET_PHASE("SP", listOf("Completed", "Failed")),
	UNKNOWN("", listOf()),
}