package com.custom.keyboard

class TranslatorEngine {

    // Offline Core Dictionary for Instant Translation
    private val translations = mapOf(
        "hello" to mapOf("ar" to "مرحبا", "fr" to "Bonjour", "es" to "Hola"),
        "hi" to mapOf("ar" to "أهلا", "fr" to "Salut", "es" to "Hola"),
        "thanks" to mapOf("ar" to "شكرا", "fr" to "Merci", "es" to "Gracias"),
        "thank you" to mapOf("ar" to "شكرا جزيلا", "fr" to "Merci beaucoup", "es" to "Muchas gracias"),
        "please" to mapOf("ar" to "من فضلك", "fr" to "S'il vous plaît", "es" to "Por favor"),
        "yes" to mapOf("ar" to "نعم", "fr" to "Oui", "es" to "Sí"),
        "no" to mapOf("ar" to "لا", "fr" to "Non", "es" to "No"),
        "okay" to mapOf("ar" to "حسنا", "fr" to "D'accord", "es" to "De acuerdo"),
        "good morning" to mapOf("ar" to "صباح الخير", "fr" to "Bonjour", "es" to "Buenos días"),
        "good night" to mapOf("ar" to "تصبح على خير", "fr" to "Bonne nuit", "es" to "Buenas noches"),
        "how are you" to mapOf("ar" to "كيف حالك", "fr" to "Comment ça va", "es" to "¿Cómo estás?"),
        "welcome" to mapOf("ar" to "أهلا وسهلا", "fr" to "Bienvenue", "es" to "Bienvenido"),
        "sorry" to mapOf("ar" to "آسف", "fr" to "Désolé", "es" to "Lo siento"),
        "see you" to mapOf("ar" to "إلى اللقاء", "fr" to "À bientôt", "es" to "Hasta luego"),
        "congratulations" to mapOf("ar" to "مبروك", "fr" to "Félicitations", "es" to "Felicidades"),
        "love" to mapOf("ar" to "حب", "fr" to "Amour", "es" to "Amor"),
        "peace" to mapOf("ar" to "سلام", "fr" to "Paix", "es" to "Paz")
    )

    fun translate(query: String, targetLangCode: String): String? {
        val clean = query.trim().lowercase()
        val entry = translations[clean] ?: return null
        return entry[targetLangCode]
    }

    // Quick phrases list for instant selection
    fun getQuickPhrases(targetLangCode: String): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        for ((en, targetMap) in translations) {
            val trans = targetMap[targetLangCode]
            if (trans != null) {
                list.add(Pair(en.replaceFirstChar { it.uppercase() }, trans))
            }
        }
        return list
    }
}
