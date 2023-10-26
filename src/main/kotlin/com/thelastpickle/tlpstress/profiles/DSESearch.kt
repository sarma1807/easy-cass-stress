package com.thelastpickle.tlpstress.profiles

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

import com.datastax.driver.core.PreparedStatement
import com.datastax.driver.core.Session
import com.fasterxml.jackson.annotation.JsonInclude
import com.thelastpickle.tlpstress.PartitionKey
import com.thelastpickle.tlpstress.StressContext
import com.thelastpickle.tlpstress.WorkloadParameter
import com.thelastpickle.tlpstress.generators.Field
import com.thelastpickle.tlpstress.generators.FieldFactory
import com.thelastpickle.tlpstress.generators.FieldGenerator
import com.thelastpickle.tlpstress.generators.functions.Book
import com.thelastpickle.tlpstress.generators.functions.Random
import java.util.concurrent.ThreadLocalRandom


class DSESearch : IStressProfile {

    val TABLE: String = "dse_search"
    val MIN_VALUE_TEXT_SIZE=5
    val MAX_VALUE_TEXT_SIZE=10

    lateinit var insert: PreparedStatement
    lateinit var select: PreparedStatement
    lateinit var delete: PreparedStatement

    val mapper = jacksonObjectMapper()

    @WorkloadParameter("Enable global queries.")
    var global = false

    @WorkloadParameter(description = "Max rows per partition")
    var rows = 10000

    override fun prepare(session: Session) {
        insert = session.prepare("INSERT INTO $TABLE (key, c, value_text) VALUES (?, ?, ?)")
        select = session.prepare("SELECT key, c, value_text from $TABLE WHERE solr_query = ?")

        delete = session.prepare("DELETE from $TABLE WHERE key = ? and c = ?")
    }

    override fun schema(): List<String> {
        return listOf("""CREATE TABLE IF NOT EXISTS $TABLE (
                    key text,
                    c int,
                    value_text text,
                    PRIMARY KEY (key, c)
            )""".trimIndent(),
            """CREATE SEARCH INDEX IF NOT EXISTS ON $TABLE WITH COLUMNS value_text
            """.trimIndent())
    }

    override fun getRunner(context: StressContext): IStressRunner {
        val value = context.registry.getGenerator(TABLE, "value_text")
        val regex = "[^a-zA-Z0-9]".toRegex()

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        data class SolrQuery(
            var q: String,
            var fq: String
        )

        return object : IStressRunner {

            val c_id = ThreadLocalRandom.current()
            val nextRowId : Int get() = c_id.nextInt(0, rows)

            override fun getNextMutation(partitionKey: PartitionKey): Operation {
                val bound = insert.bind(partitionKey.getText(), nextRowId, value.getText())
                return Operation.Mutation(bound)
            }

            override fun getNextSelect(partitionKey: PartitionKey): Operation {
                val valueValue = value.getText().substringBeforeLast(" ")
                                                .replace(regex, " ")
                                                .trim()


                val query = SolrQuery(q= "value_text:($valueValue)",
                    fq = if (!global) "key:${partitionKey.getText()}" else ""
                )

                val queryString = mapper.writeValueAsString(query)

                val bound = select.bind(queryString)
                return Operation.SelectStatement(bound)
            }

            override fun getNextDelete(partitionKey: PartitionKey) =
                Operation.Deletion(delete.bind(partitionKey.getText(), nextRowId))


        }
    }

    override fun getFieldGenerators(): Map<Field, FieldGenerator> {
        val search = FieldFactory(TABLE)
        return mapOf(Field(TABLE, "value_text") to Book().apply { min=MIN_VALUE_TEXT_SIZE; max=MAX_VALUE_TEXT_SIZE },
                     Field(TABLE, "value_int")  to Random().apply {min=0; max=100})
    }


}