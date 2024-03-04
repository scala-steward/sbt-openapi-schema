package com.github.eikek.sbt.openapi.impl

import com.github.eikek.sbt.openapi.PartConv._
import com.github.eikek.sbt.openapi._

object ScalaCode {
  val primitiveTypeMapping: TypeMapping =
    TypeMapping(
      Type.Bool -> TypeDef("Boolean", Imports.empty),
      Type.String -> TypeDef("String", Imports.empty),
      Type.Int32 -> TypeDef("Int", Imports.empty),
      Type.Int64 -> TypeDef("Long", Imports.empty),
      Type.Float32 -> TypeDef("Float", Imports.empty),
      Type.Float64 -> TypeDef("Double", Imports.empty),
      Type.BigDecimal -> TypeDef("BigDecimal", Imports.empty),
      Type.Uuid -> TypeDef("UUID", Imports("java.util.UUID")),
      Type.Url -> TypeDef("URL", Imports("java.net.URL")),
      Type.Uri -> TypeDef("URI", Imports("java.net.URI")),
      Type.Date(Type.TimeRepr.String) -> TypeDef(
        "LocalDate",
        Imports("java.time.LocalDate")
      ),
      Type.Date(Type.TimeRepr.Number) -> TypeDef(
        "LocalDate",
        Imports("java.time.LocalDate")
      ),
      Type.DateTime(Type.TimeRepr.String) -> TypeDef(
        "LocalDateTime",
        Imports("java.time.LocalDateTime")
      ),
      Type.DateTime(Type.TimeRepr.Number) -> TypeDef(
        "LocalDateTime",
        Imports("java.time.LocalDateTime")
      ),
      Type.Json -> TypeDef("Json", Imports.empty)
    )

  def defaultTypeMapping(cm: CustomMapping): TypeMapping = {
    case Type.Sequence(param) =>
      defaultTypeMapping(cm)(param).map(el =>
        cm.changeType(TypeDef(s"List[${el.name}]", el.imports))
      )
    case Type.Map(key, value) =>
      for {
        k <- defaultTypeMapping(cm)(key)
        v <- defaultTypeMapping(cm)(value)
      } yield cm.changeType(TypeDef(s"Map[${k.name},${v.name}]", k.imports ++ v.imports))
    case Type.Ref(name) =>
      val srcRef = SingularSchemaClass(name)
      Some(TypeDef(resolveSchema(srcRef, cm).name, Imports.empty))
    case t =>
      primitiveTypeMapping(t).map(cm.changeType)
  }

  def enclosingObject(
      enclosingTraitName: String,
      cfg: ScalaConfig
  ): PartConv[SourceFile] = {
    val parents: PartConv[List[Superclass]] =
      listSplit(
        constant[Superclass]("extends") ~ superclass,
        constant("with") ~ forListSep(superclass, Part(", "))
      )

    val fieldPart: PartConv[Field] = {
      val prefix = cfg.modelType match {
        case ScalaModelType.CaseClass => ""
        case ScalaModelType.Trait     => "val "
      }
      cond(
        f => f.nullablePrimitive,
        PartConv.of(prefix) + fieldName + PartConv.of(": Option[") + fieldType + PartConv
          .of("]"),
        PartConv.of(prefix) + fieldName + PartConv.of(": ") + fieldType
      )
    }

    val internalModel = cfg.modelType match {
      case ScalaModelType.CaseClass =>
        constant("case class") ~ sourceName ~ forList(annotation, _ ++ _)
          .contramap[SourceFile](_.ctorAnnot) ~ constant("(") ++
          forListSep(fieldPart, Part(", "))
            .map(_.indent(2))
            .contramap(_.fields.filterNot(_.prop.discriminator)) ++
          constant(s") extends $enclosingTraitName")
      case ScalaModelType.Trait =>
        constant("trait") ~ sourceName ~ forList(annotation, _ ++ _)
          .contramap[SourceFile](_.ctorAnnot) ~ constant(
          s"extends $enclosingTraitName"
        ) ~ constant("{") ++
          forList(fieldPart, _ ++ _)
            .map(_.indent(2))
            .contramap(_.fields.filterNot(_.prop.discriminator)) ++
          constant(s"}")
    }

    val internalModels: PartConv[SourceFile] =
      forList(internalModel, _ ++ _).contramap(_.internalSchemas)

    val discriminantImports = cfg.modelType match {
      case ScalaModelType.CaseClass =>
        constant(
          "implicit val customConfig: io.circe.generic.extras.Configuration = io.circe.generic.extras.Configuration.default.withDefaults.withDiscriminator(\""
        ).map(_.indent(2)) + discriminantType + constant("\")")
      case ScalaModelType.Trait =>
        PartConv.empty[SourceFile]
    }

    constant("object") ~ sourceName ~ forList(annotation, _ ++ _)
      .contramap[SourceFile](_.ctorAnnot) ~ constant("{") ++ discriminantImports ++
      internalModels.map(_.indent(2)) ++
      cfg.json.companion.map(_.indent(2)) ++
      constant("}") ~ parents.map(_.newline).contramap(_.parents)
  }

  def sealedTrait: PartConv[SourceFile] = {
    val fieldPart: PartConv[Field] =
      cond(
        f => f.nullablePrimitive,
        constant("val") ~ fieldName + PartConv.of(": Option[") + fieldType + PartConv.of(
          "]"
        ),
        constant("val") ~ fieldName + PartConv.of(": ") + fieldType
      )
    val parents: PartConv[List[Superclass]] =
      listSplit(
        constant[Superclass]("extends") ~ superclass,
        constant("with") ~ forListSep(superclass, Part(", "))
      )
    constant("sealed trait") ~ sourceName ~ forList(annotation, _ ++ _)
      .contramap[SourceFile](_.ctorAnnot) ~ constant("{") ++
      forListSep(fieldPart, Part("; "))
        .map(_.indent(2))
        .contramap(_.fields.filterNot(_.prop.discriminator)) ++
      constant("}") ~ parents.map(_.newline).contramap(_.parents)
  }

  def caseClass: PartConv[SourceFile] = {
    val fieldPart: PartConv[Field] =
      cond(
        f => f.nullablePrimitive,
        fieldName + PartConv.of(": Option[") + fieldType + PartConv.of("]"),
        fieldName + PartConv.of(": ") + fieldType
      )
    val parents: PartConv[List[Superclass]] =
      listSplit(
        constant[Superclass]("extends") ~ superclass,
        constant("with") ~ forListSep(superclass, Part(", "))
      )
    constant("case class") ~ sourceName ~ forList(annotation, _ ++ _)
      .contramap[SourceFile](_.ctorAnnot) ~ constant("(") ++
      forListSep(fieldPart, Part(", ")).map(_.indent(2)).contramap(_.fields) ++
      constant(")") ~ parents.map(_.newline).contramap(_.parents)
  }

  def traitCode: PartConv[SourceFile] = {
    val fieldPart: PartConv[Field] =
      cond(
        f => f.nullablePrimitive,
        PartConv.of("val ") + fieldName + PartConv.of(": Option[") + fieldType + PartConv
          .of("]"),
        PartConv.of("val ") + fieldName + PartConv.of(": ") + fieldType
      )
    val parents: PartConv[List[Superclass]] =
      listSplit(
        constant[Superclass]("extends") ~ superclass,
        constant("with") ~ forListSep(superclass, Part(", "))
      )
    constant("trait") ~ sourceName ~ forList(annotation, _ ++ _)
      .contramap[SourceFile](_.ctorAnnot) ~ parents
      .map(_.newline)
      .contramap(_.parents) ~ constant("{") ++
      forList(fieldPart, _ ++ _).map(_.indent(2)).contramap(_.fields) ++
      constant("}")
  }

  def fileHeader: PartConv[SourceFile] =
    pkg.contramap[SourceFile](_.pkg) ++
      imports.map(_.newline).contramap(_.imports) ++
      doc.contramap(_.doc)

  def generate(sc: SchemaClass, pkg: Pkg, cfg: ScalaConfig): (String, String) =
    sc match {
      case _: SingularSchemaClass =>
        val src = resolveSchema(sc, cfg.mapping).copy(pkg = pkg).modify(cfg.json.resolve)
        val scalaModel = cfg.modelType match {
          case ScalaModelType.CaseClass => caseClass
          case ScalaModelType.Trait     => traitCode
        }
        val conv = fileHeader ++ scalaModel ++ cfg.json.companion
        (src.name, conv.toPart(src).render)
      case _: DiscriminantSchemaClass =>
        val src = resolveSchema(sc, cfg.mapping).copy(pkg = pkg).modify(cfg.json.resolve)
        val conv = fileHeader ++ sealedTrait ++ enclosingObject(src.name, cfg)
        (src.name, conv.toPart(src).render)
    }

  def resolveSchema(sc: SchemaClass, cm: CustomMapping): SourceFile = {
    val tm = defaultTypeMapping(cm)
    SchemaClass.resolve(sc, Pkg(""), tm, cm)
  }
}
