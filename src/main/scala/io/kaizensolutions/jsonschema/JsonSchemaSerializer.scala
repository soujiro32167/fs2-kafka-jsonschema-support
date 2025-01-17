package io.kaizensolutions.jsonschema

import cats.effect.{Ref, Sync}
import cats.syntax.all._
import com.fasterxml.jackson.databind.JsonNode
import fs2.kafka.{RecordSerializer, Serializer}
import io.circe.Encoder
import io.circe.jackson.circeToJackson
import io.circe.syntax._
import io.confluent.kafka.schemaregistry.ParsedSchema
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import io.confluent.kafka.schemaregistry.json.JsonSchema
import io.confluent.kafka.schemaregistry.json.jackson.Jackson
import io.kaizensolutions.jsonschema.JsonSchemaSerializer.SubjectSchema

import java.io.{ByteArrayOutputStream, IOException}
import java.nio.ByteBuffer
import scala.reflect.ClassTag

/**
 * Look at Confluent's KafkaJsonSchemaSerializer -> AbstractKafkaJsonSchemaSerializer -> AbstractKafkaSchemaSerDe
 *
 * The real implementation does the following (minus a few details):
 *
 * 1. call configure (provide isKey to figure out the subject name)
 *    - determine whether we automatically register the schema (involves using the subject name and the topic)
 *    - or determine whether we use the latest schema
 *    - determine whether to use strict compatibility
 *    - determine whether to validate the body against the payload and fail if not correct
 * 2. serialization of a message
 *    - extract the JSON Schema of the message (we don't need to do this every time because our Serializers are very specific)
 *    - register the schema (provided configuration) or use the latest schema
 *    - validate message against JSON schema (provided configuration)
 *    - write out message: magic byte ++ subject id ++ payload
 *
 *   see AbstractKafkaJsonSchemaDeserializer for deserialization details
 */
object JsonSchemaSerializer {
  private[jsonschema] final case class SubjectSchema(
    subject: String,
    schema: ParsedSchema
  )

  def apply[F[_]: Sync, A: Encoder](
    settings: JsonSchemaSerializerSettings,
    client: SchemaRegistryClient
  )(implicit jsonSchema: json.Schema[A], tag: ClassTag[A]): F[RecordSerializer[F, A]] =
    toJsonSchema(jsonSchema, settings.jsonSchemaId)
      .flatMap(schema => apply(settings, client, schema))

  def apply[F[_] : Sync, A: Encoder](
    settings: JsonSchemaSerializerSettings,
    client: SchemaRegistryClient,
    schema: JsonSchema
  ): F[RecordSerializer[F, A]] = {

    val fCache = Ref.of[F, Map[SubjectSchema, ParsedSchema]](Map.empty)

    fCache.map { cache =>
      val serializer = JsonSchemaSerializer[F, A](client, settings, cache, schema)
      RecordSerializer.instance(
        forKey = Sync[F].pure(serializer.jsonSchemaSerializer(true)),
        forValue = Sync[F].pure(serializer.jsonSchemaSerializer(false))
      )
    }
  }
}

private[jsonschema] final case class JsonSchemaSerializer[F[_]: Sync, A: Encoder] private (
  client: SchemaRegistryClient,
  settings: JsonSchemaSerializerSettings,
  cache: Ref[F, Map[SubjectSchema, ParsedSchema]],
  clientSchema: JsonSchema
) {
  private val MagicByte: Byte = 0x0
  private val IdSize: Int     = 4

  private val objectMapper = Jackson.newObjectMapper()
  private val objectWriter = objectMapper.writer()

  def jsonSchemaSerializer(isKey: Boolean): Serializer[F, A] = {
    val mkSubject = subjectName(isKey) _

    Serializer.instance[F, A] { (topic, _, data) =>
      val jsonPayload: JsonNode = circeToJackson(data.asJson)
      val subject               = mkSubject(topic)

      val fSchema: F[JsonSchema] =
        if (!settings.automaticRegistration && settings.useLatestVersion)
          lookupLatestVersion(subject, clientSchema, cache, settings.latestCompatStrict)
            .map(_.asInstanceOf[JsonSchema])
        else Sync[F].pure(clientSchema)

      val fId: F[Int] =
        if (settings.automaticRegistration) registerSchema(subject, clientSchema)
        else if (settings.useLatestVersion)
          lookupLatestVersion(subject, clientSchema, cache, settings.latestCompatStrict)
            .flatMap(s => getId(subject, s.asInstanceOf[JsonSchema]))
        else getId(subject, clientSchema)

      for {
        schema <- fSchema
        _      <- validatePayload(schema, jsonPayload)
        id     <- fId
        bytes  <- Sync[F].delay {
                    val payloadBytes = objectWriter.writeValueAsBytes(jsonPayload)
                    val baos         = new ByteArrayOutputStream()
                    baos.write(MagicByte.toInt)
                    baos.write(ByteBuffer.allocate(IdSize).putInt(id).array())
                    baos.write(payloadBytes)
                    val bytes        = baos.toByteArray
                    baos.close()
                    bytes
                  }
      } yield bytes
    }
  }

  private def validatePayload(schema: JsonSchema, jsonPayload: JsonNode): F[Unit] =
    if (settings.validatePayload) Sync[F].delay(schema.validate(jsonPayload))
    else Sync[F].unit

  private def subjectName(isKey: Boolean)(topic: String): String =
    if (isKey) s"$topic-key" else s"$topic-value"

  private def registerSchema(subject: String, jsonSchema: JsonSchema): F[Int] =
    Sync[F].delay(client.register(subject, jsonSchema))

  private def getId(subject: String, jsonSchema: JsonSchema): F[Int] =
    Sync[F].delay(client.getId(subject, jsonSchema))

  private def fetchLatest(subject: String): F[ParsedSchema] =
    Sync[F]
      .delay(client.getLatestSchemaMetadata(subject))
      .flatMap { metadata =>
        Sync[F].delay {
          // This requires JSON support to be configured in the Schema Registry Client
          client.parseSchema(
            metadata.getSchemaType,
            metadata.getSchema,
            metadata.getReferences
          )
        }.flatMap { optSchema =>
          if (optSchema.isPresent) Sync[F].pure(optSchema.get)
          else
            Sync[F].delay(new JsonSchema(metadata.getSchema).validate()) >>
              // successfully parsed the schema locally means that the client was not properly configured
              Sync[F].raiseError[ParsedSchema](
                new RuntimeException(
                  "Please enable JSON support in SchemaRegistryClientSettings by using withJsonSchemaSupport"
                )
              )
        }
      }

  private def lookupLatestVersion(
    subject: String,
    schema: ParsedSchema,
    cache: Ref[F, Map[SubjectSchema, ParsedSchema]],
    latestCompatStrict: Boolean
  ): F[ParsedSchema] = {
    val ss = SubjectSchema(subject, schema)
    for {
      map           <- cache.get
      result         = map.get(ss)
      latestVersion <- result match {
                         case Some(cached) => Sync[F].pure(cached)
                         case None         => fetchLatest(subject)
                       }
      // the latest version must be backwards compatible with the current schema
      // this does not test forward compatibility to allow unions
      compatIssues   = latestVersion.isBackwardCompatible(schema)
      _             <- if (latestCompatStrict && !compatIssues.isEmpty)
                         Sync[F].raiseError(
                           new IOException(s"Incompatible schema: ${compatIssues.toArray.mkString(", ")}")
                         )
                       else Sync[F].unit
      _             <- result match {
                         case Some(_) => Sync[F].unit
                         case None    => cache.update(_ + (ss -> latestVersion))
                       }
    } yield latestVersion
  }
}
