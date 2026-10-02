package com.reef.platform.calcify

import java.sql.DriverManager
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.common.Uuid

/** Startup registration check only. Never reads SQL in per-trade resolution. */
internal object CalcifySourceRegistration {
    fun verify(bootstrap:String,source:String,generation:Int,jdbc:String,user:String,password:String,bindIfAbsent:Boolean=true):String {
        val topicId=AdminClient.create(Properties().apply {put("bootstrap.servers",bootstrap)}).use {admin->
            val description=admin.describeTopics(listOf(source)).allTopicNames().get(10,TimeUnit.SECONDS).getValue(source)
            require(description.topicId()!=Uuid.ZERO_UUID) {"broker did not provide source topic ID"}
            description.topicId().toString()
        }
        DriverManager.getConnection(jdbc,user,password).use {db->
            db.autoCommit=false
            try {
                db.prepareStatement("SELECT source_topic, source_topic_id FROM runtime.calcify_source_generations WHERE source_generation = ? FOR UPDATE").use {query->
                    query.setInt(1,generation)
                    query.executeQuery().use {rows->
                        require(rows.next() && rows.getString(1)==source) {"unregistered Calcify source generation"}
                        val registered=rows.getString(2)
                        if(registered==null) {
                            require(bindIfAbsent) {"source generation must be bound by extractor before resolver startup"}
                            db.prepareStatement("UPDATE runtime.calcify_source_generations SET source_topic_id = ? WHERE source_generation = ?").use {it.setString(1,topicId);it.setInt(2,generation);it.executeUpdate()}
                        } else require(registered==topicId) {"source topic recreated: register next Calcify source generation"}
                    }
                }
                db.commit()
            } catch(ex:Exception) {db.rollback();throw ex}
        }
        return topicId
    }

    /** Binds (or validates) one non-source Calcify topic's identity for this generation.
     * Always binds on first sight: unlike source, no earlier stage registers these first. */
    fun verifyTopic(jdbc:String,user:String,password:String,generation:Int,role:String,topicName:String,topicId:String) {
        require(topicId.isNotBlank()) {"Calcify $role topic ID must not be blank"}
        DriverManager.getConnection(jdbc,user,password).use {db->
            db.autoCommit=false
            try {
                db.prepareStatement("SELECT topic_name, topic_id FROM runtime.calcify_topic_identities WHERE source_generation = ? AND role = ? FOR UPDATE").use {query->
                    query.setInt(1,generation);query.setString(2,role)
                    query.executeQuery().use {rows->
                        if(rows.next()) {
                            require(rows.getString(1)==topicName && rows.getString(2)==topicId) {"Calcify $role topic recreated: register next Calcify source generation"}
                        } else {
                            db.prepareStatement("INSERT INTO runtime.calcify_topic_identities(source_generation,role,topic_name,topic_id) VALUES (?,?,?,?)").use {insert->
                                insert.setInt(1,generation);insert.setString(2,role);insert.setString(3,topicName);insert.setString(4,topicId);insert.executeUpdate()
                            }
                        }
                    }
                }
                db.commit()
            } catch(ex:Exception) {db.rollback();throw ex}
        }
    }
}
