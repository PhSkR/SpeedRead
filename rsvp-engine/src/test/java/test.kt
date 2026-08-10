import com.speedread.rsvp.engine.*

fun main() {
    val processor = DefaultTextProcessor()
    val text = (1..100).joinToString(" ") { "w$it" }
    val result = processor.processText(text, RsvpSettings(chunkSize = 5))
    println("Words: ${result.size}")
}