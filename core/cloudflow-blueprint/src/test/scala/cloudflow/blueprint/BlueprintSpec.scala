/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cloudflow.blueprint

import com.typesafe.config._
import org.scalatest._

class BlueprintSpec extends WordSpec with MustMatchers with EitherValues with OptionValues {
  case class Foo(name: String)
  case class Bar(name: String)

  val separator = java.io.File.separator

  import BlueprintBuilder._

  "A blueprint" should {
    "fail verification if streamlets, connections and streamlet descriptors are empty" in {
      val blueprint = Blueprint().verify

      blueprint.problems must contain theSameElementsAs Vector(EmptyStreamlets, EmptyStreamletDescriptors)
    }

    "fail verification if no streamlets are used" in {
      val ingress = randomStreamlet().asIngress[Foo]
      val processor = randomStreamlet().asProcessor[Foo, Foo]
      val blueprint = Blueprint().define(Vector(ingress, processor))

      blueprint.problems must contain theSameElementsAs Vector(EmptyStreamlets)
    }

    List("a", "abcd", "a-b", "ab--cd", "1ab2", "1ab", "1-2").foreach { name ⇒
      s"verify if it uses a streamlet with a valid name ('${name}')" in {
        val ingress = randomStreamlet().asIngress[Foo]

        Blueprint()
          .define(Vector(ingress))
          .use(ingress.ref(name))
          .problems mustBe empty
      }
    }

    List("A", "aBcd", "9B", "-ab", "ab-", "a_b", "a/b", "a+b").foreach { name ⇒
      s"fail verification if it uses a streamlet with an invalid name ('${name}')" in {
        val ingress = randomStreamlet().asIngress[Foo]

        Blueprint()
          .define(Vector(ingress))
          .use(ingress.ref(name))
          .problems must contain theSameElementsAs Vector(InvalidStreamletName(name))
      }
    }

    List("-ab", "ab-", "1ab", "a/b", "a+b").foreach { className ⇒
      s"fail verification if it uses a streamlet with an invalid class name ('${className}')" in {
        val ingress = streamlet(className).asIngress[Foo]
        val ref = ingress.randomRef

        Blueprint()
          .define(Vector(ingress))
          .use(ref)
          .problems must contain theSameElementsAs Vector(InvalidStreamletClassName(ref.name, className))
      }
    }

    List("a", "abcd", "a-b", "ab--cd", "1ab2", "1ab", "1-2").foreach { outletName ⇒
      s"verify if it uses a streamlet with a valid outlet name ('${outletName}')" in {
        val ingress = randomStreamlet().asIngress[Foo](outletName)

        Blueprint()
          .define(Vector(ingress))
          .use(ingress.randomRef)
          .problems mustBe empty
      }
    }

    List("A", "aBcd", "9B", "-ab", "ab-", "a_b", "a/b", "a+b").foreach { outletName ⇒
      s"fail verification if it uses a streamlet with an invalid outlet name ('${outletName}')" in {
        val ingress = randomStreamlet().asIngress[Foo](outletName)

        Blueprint()
          .define(Vector(ingress))
          .use(ingress.randomRef)
          .problems must contain theSameElementsAs Vector(InvalidOutletName(ingress.className, outletName))
      }
    }

    List("a", "abcd", "a-b", "ab--cd", "1ab2", "1ab", "1-2").foreach { inletName ⇒
      s"verify if it uses a streamlet with a valid inlet name ('${inletName}')" in {
        val ingress = randomStreamlet().asIngress[Foo]
        val processor = randomStreamlet().asProcessor[Foo, Foo](inletName = inletName)
        val ingressRef = ingress.ref("foo")
        val processorRef = processor.ref("bar")

        Blueprint()
          .define(Vector(ingress, processor))
          .use(ingressRef)
          .use(processorRef)
          .connect(ingressRef.out, processorRef.inlet(inletName))
          .problems mustBe empty
      }
    }

    List("A", "aBcd", "9B", "-ab", "ab-", "a_b", "a/b", "a+b").foreach { inletName ⇒
      s"fail verification if it uses a streamlet with an invalid inlet name ('${inletName}')" in {
        val ingress = randomStreamlet().asIngress[Foo]
        val processor = randomStreamlet().asProcessor[Foo, Foo](inletName = inletName)
        val ingressRef = ingress.ref("foo")
        val processorRef = processor.ref("bar")

        Blueprint()
          .define(Vector(ingress, processor))
          .use(ingressRef)
          .use(processorRef)
          .connect(ingressRef.out, processorRef.inlet(inletName))
          .problems must contain(InvalidInletName(processor.className, inletName))
      }
    }

    "be able to define and use streamlets" in {
      val ingress = randomStreamlet().asIngress[Foo]
      val processor = randomStreamlet().asProcessor[Foo, Foo]
      val ingressRef = ingress.randomRef

      val blueprint = Blueprint()
        .define(Vector(ingress, processor))
        .use(ingressRef)

      blueprint.problems mustBe empty
      blueprint.streamlets(0) mustBe ingressRef.copy(
        verified = Some(VerifiedStreamlet(ingressRef.name, ingress))
      )
    }

    "be able to define, use and connect streamlets" in {
      val ingress = randomStreamlet().asIngress[Foo]
      val processor = randomStreamlet().asProcessor[Foo, Foo]
      val ingressRef = ingress.ref("foo")
      val processorRef = processor.ref("bar")

      val blueprint = Blueprint()
        .define(Vector(ingress, processor))
        .use(ingressRef)
        .use(processorRef)
        .connect(ingressRef.out, processorRef.in)

      blueprint.problems mustBe empty
      blueprint.streamlets(0) mustBe ingressRef.copy(
        verified = Some(VerifiedStreamlet(ingressRef.name, ingress))
      )
      blueprint.streamlets(1) mustBe processorRef.copy(
        verified = Some(VerifiedStreamlet(processorRef.name, processor))
      )
    }

    "be able to define, use and connect streamlets, even if their class names partially overlap" in {
      val ingress = streamlet("com.example.Foo").asIngress[Foo]
      val processor1 = streamlet("com.example.Fooz").asProcessor[Foo, Foo]
      val processor2 = streamlet("com.acme.SnaFoo").asProcessor[Foo, Bar]
      val processor3 = streamlet("io.github.FooBar").asProcessor[Bar, Bar]

      val ingressRef = ingress.ref("foo")
      val processor1Ref = processor1.ref("fooz")
      val processor2Ref = processor2.ref("bar")
      val processor3Ref = processor3.ref("foobar")

      val blueprint = Blueprint()
        .define(Vector(ingress, processor1, processor2, processor3))
        .use(ingressRef)
        .use(processor1Ref)
        .use(processor2Ref)
        .use(processor3Ref)
        .connect(ingressRef.out, processor1Ref.in)
        .connect(processor1Ref.out, processor2Ref.in)
        .connect(processor2Ref.out, processor3Ref.in)

      blueprint.problems mustBe empty
    }

    "not allow connecting to a streamlet with more than one inlet using a short name" in {
      val ingress = randomStreamlet().asIngress[Foo]
      val merge = randomStreamlet().asMerge[Foo, Bar, Foo]
      val blueprint = connectedBlueprint(ingress, merge)
      val ingressRef = blueprint.streamlets(0)
      val mergeRef = blueprint.streamlets(1)

      blueprint
        .connect(StreamletConnection(ingressRef.name, mergeRef.name))
        .problems.size mustBe 2
    }

    "be able to connect to the correct inlet using a full port path when the streamlet has more than one inlet" in {
      val ingress = randomStreamlet().asIngress[Foo]
      val merge = randomStreamlet().asMerge[Foo, Bar, Foo]
      val ingressRef = ingress.ref("foo")
      val mergeRef = merge.ref("bar")

      val blueprint =
        Blueprint()
          .define(Vector(ingress, merge))
          .use(ingressRef)
          .use(mergeRef)
          .verify

      val connected = blueprint.connect(ingressRef.name, mergeRef.in0)
      connected.problems.size mustBe 1
      connected.problems mustBe Vector(
        UnconnectedInlets(Vector(
          UnconnectedInlet("bar", merge.in1))
        )
      )
    }

    "not fail verification with UnconnectedInlets for already reported IllegalConnection and IncompatibleSchema problems" in {
      val ingress = randomStreamlet().asIngress[Foo]
      val processor = randomStreamlet().asProcessor[Foo, Bar]
      val egress = randomStreamlet().asEgress[Bar].withConfigParameters(ConfigParameterDescriptor("target-uri", "", "string", Some("^.{1,65535}$"), None))
      val ingressRef = ingress.randomRef
      val processor1Ref = processor.randomRef
      val processor2Ref = processor.randomRef
      val egress1Ref = egress.randomRef
      val egress2Ref = egress.randomRef

      val blueprint = Blueprint()
        .define(Vector(ingress, processor, egress))
        .use(ingressRef)
        .use(processor1Ref)
        .use(processor2Ref)
        .use(egress1Ref)
        .use(egress2Ref)
        .connect(ingressRef.out, processor1Ref.in)
        .connect(ingressRef.out, processor2Ref.in)
        .connect(processor1Ref.out, egress1Ref.in)
        .connect(processor2Ref.out, egress1Ref.in)
        .connect(ingressRef.out, egress2Ref.in)
        .upsertStreamletRef(egress1Ref.name)
        .upsertStreamletRef(egress2Ref.name)

      blueprint.problems.collect { case unconnected: UnconnectedInlets ⇒ unconnected }.size mustBe 0

      blueprint.problems mustBe Vector(
        IllegalConnection(
          Vector(
            VerifiedPortPath(processor1Ref.name, Some("out")),
            VerifiedPortPath(processor2Ref.name, Some("out"))
          ),
          VerifiedPortPath(egress1Ref.name, Some("in"))
        ),
        IncompatibleSchema(
          VerifiedPortPath(ingressRef.name, Some("out")),
          VerifiedPortPath(egress2Ref.name, Some("in"))
        )
      )
    }

    "fail verification for configuration parameters with invalid validation patterns" in {
      val blueprint = createBlueprintWithConfigurationParameter(ConfigParameterDescriptor("test-parameter", "", "string", Some("^.{1,65535$"), None))

      blueprint.problems must not be empty
      blueprint.problems.head mustBe a[InvalidValidationPatternConfigParameter]
    }

    "fail verification for configuration parameters with invalid default regexp value" in {
      val blueprint = createBlueprintWithConfigurationParameter(
        ConfigParameterDescriptor(
          "log-level",
          "Provide one of the following log levels, debug,info, warning or error",
          "string",
          Some("^debug|info|warning|error$"),
          Some("invalid-default-value"))
      )

      blueprint.problems must not be empty
      blueprint.problems.head mustBe a[InvalidDefaultValueInConfigParameter]
    }

    "fail verification for configuration parameters with invalid default duration" in {
      val blueprint = createBlueprintWithConfigurationParameter(
        ConfigParameterDescriptor(
          "duration-value",
          "Provide a duration of time",
          "duration",
          None,
          Some("20 parsec"))
      )

      blueprint.problems must not be empty
      blueprint.problems.head mustBe a[InvalidDefaultValueInConfigParameter]
    }

    "be able to validate a correct duration in a default value" in {
      val blueprint = createBlueprintWithConfigurationParameter(
        ConfigParameterDescriptor(
          "duration-value",
          "Provide a duration of time",
          "duration",
          None,
          Some("1 minute"))
      )

      blueprint.problems mustBe empty
    }

    "be able to validate a correct memory size in a default value" in {
      val blueprint = createBlueprintWithConfigurationParameter(
        ConfigParameterDescriptor(
          "memorysize-value",
          "Provide a memory size",
          "memorysize",
          None,
          Some("20 M"))
      )

      blueprint.problems mustBe empty
    }

    "fail verification for configuration parameters with invalid default memory size" in {
      val blueprint = createBlueprintWithConfigurationParameter(
        ConfigParameterDescriptor(
          "memorysize-value",
          "Provide a memory size",
          "memorysize",
          None,
          Some("42 pigeons"))
      )

      blueprint.problems must not be empty
      blueprint.problems.head mustBe a[InvalidDefaultValueInConfigParameter]
    }

    "fail verification for configuration parameters with duplicate keys" in {
      val blueprint = createBlueprintWithConfigurationParameter(
        ConfigParameterDescriptor(
          "memorysize-value",
          "Provide a memory size",
          "memorysize",
          None,
          Some("42 m")),
        ConfigParameterDescriptor(
          "memorysize-value",
          "Another memory size parameter with a duplicate name",
          "memorysize",
          None,
          Some("52m"))
      )

      blueprint.problems must not be empty
      blueprint.problems.head mustBe a[DuplicateConfigParameterKeyFound]
    }

    "fail verification for volume mounts with duplicate names" in {
      val blueprint = createBlueprintWithVolumeMounts(
        VolumeMountDescriptor(
          "ml-data",
          separator + "some-path",
          "ReadWriteMany"),
        VolumeMountDescriptor(
          "ml-data",
          separator + "some-other-path",
          "ReadWriteMany"))

      blueprint.problems must not be empty
      blueprint.problems.head mustBe a[DuplicateVolumeMountName]
      blueprint.problems.head.asInstanceOf[DuplicateVolumeMountName].name mustBe "ml-data"
    }

    "fail verification for volume mounts with duplicate path" in {
      val blueprint = createBlueprintWithVolumeMounts(
        VolumeMountDescriptor(
          "ml-data",
          separator + "some-path",
          "ReadWriteMany"),
        VolumeMountDescriptor(
          "other-ml-data",
          separator + "some-path",
          "ReadWriteMany"))

      blueprint.problems must not be empty
      blueprint.problems.head mustBe a[DuplicateVolumeMountPath]
      blueprint.problems.head.asInstanceOf[DuplicateVolumeMountPath].path mustBe s"${separator}some-path"
    }

    "fail verification for volume mounts with invalid names" in {
      val firstBlueprint = createBlueprintWithVolumeMounts(
        VolumeMountDescriptor(
          "-ml-data",
          separator + "some-path",
          "ReadWriteMany"))

      firstBlueprint.problems must not be empty
      firstBlueprint.problems.head mustBe a[InvalidVolumeMountName]
      firstBlueprint.problems.head.asInstanceOf[InvalidVolumeMountName].name mustBe "-ml-data"

      val secondBlueprint = createBlueprintWithVolumeMounts(
        VolumeMountDescriptor(
          "a-string-longer-than-63-characters---------------------------------------------------------------------------------------",
          separator + "some-path",
          "ReadWriteMany"))

      secondBlueprint.problems must not be empty
      secondBlueprint.problems.head mustBe a[InvalidVolumeMountName]
      secondBlueprint.problems.head.asInstanceOf[InvalidVolumeMountName].name mustBe "a-string-longer-than-63-characters---------------------------------------------------------------------------------------"
    }

    "fail verification for volume mounts with invalid paths" in {
      val blueprint = createBlueprintWithVolumeMounts(
        VolumeMountDescriptor(
          "ml-data",
          s"..${separator}some-path",
          "ReadWriteMany"))

      blueprint.problems must not be empty
      blueprint.problems.head mustBe a[BacktrackingVolumeMounthPath]
      blueprint.problems.head.asInstanceOf[BacktrackingVolumeMounthPath].name mustBe "ml-data"
    }

    "fail verification for volume mounts with non-absolute paths" in {
      val blueprint = createBlueprintWithVolumeMounts(
        VolumeMountDescriptor(
          "ml-data",
          s"some-path${separator}testing",
          "ReadWriteMany"))

      blueprint.problems must not be empty
      blueprint.problems.head mustBe a[NonAbsoluteVolumeMountPath]
      blueprint.problems.head.asInstanceOf[NonAbsoluteVolumeMountPath].name mustBe "ml-data"
    }

    "fail verification for volume mounts with empty paths" in {
      val blueprint = createBlueprintWithVolumeMounts(
        VolumeMountDescriptor(
          "ml-data",
          "",
          "ReadWriteMany"))

      blueprint.problems must not be empty
      blueprint.problems.head mustBe a[EmptyVolumeMountPath]
      blueprint.problems.head.asInstanceOf[EmptyVolumeMountPath].name mustBe "ml-data"
    }

    "check that a correct volume mount is correctly validated" in {
      val blueprint = createBlueprintWithVolumeMounts(
        VolumeMountDescriptor(
          "ml-data",
          separator + "some-path",
          "ReadWriteMany"))

      blueprint.problems mustBe empty
    }

    // ========================================================================
    // Tests related to Blueprint editing APIs, which are currently not in use!
    // These tests cover situations that cannot occur with file-based blueprints
    // ========================================================================

    "be able to update streamlets" in {
      val ingress = randomStreamlet().asIngress[Foo]
      val processor = randomStreamlet().asProcessor[Foo, Foo]
      val ingressRef = ingress.ref("foo")
      val processorRef = processor.ref("bar")

      val blueprint = Blueprint()
        .define(Vector(ingress, processor))
        .use(ingressRef)
        .connect(ingressRef.out, processorRef.in)

      val updatedRefError = blueprint.upsertStreamletRef(ingressRef.name, Some("NewClassName"))
      updatedRefError.streamlets.find(_.name == ingressRef.name).value mustBe
        StreamletRef(ingressRef.name, "NewClassName", Vector(StreamletDescriptorNotFound(ingressRef.name, "NewClassName")), None)

      val metadata = ConfigFactory.parseString("""
        {
          "window" : {
            "x" : 0
            "y" : 0
            "width" : 320
            "height" : 240
          }
        }
      """)

      val added = blueprint.upsertStreamletRef(processorRef.name, Some(processor.className), Some(metadata))
      exactly(1, added.streamlets) must have(
        'metadata(Some(metadata))
      )

      val updated = blueprint.upsertStreamletRef(ingressRef.name, Some(processor.className))
      updated.streamlets.find(_.name == ingressRef.name).value mustBe ingressRef.copy(
        className = processor.className,
        verified = Some(VerifiedStreamlet(ingressRef.name, processor))
      )

      val noUpdate = updated.upsertStreamletRef(ingressRef.name)
      noUpdate mustBe updated

      val updatedViewAttributes = updated.upsertStreamletRef(ingressRef.name, None, Some(metadata))
      updatedViewAttributes.streamlets.find(_.name == ingressRef.name).value.metadata.value mustBe metadata

      val notUpdatedViewAttributes = updatedViewAttributes.upsertStreamletRef(ingressRef.name, None, None)
      notUpdatedViewAttributes mustBe updatedViewAttributes
    }

    "be able to remove streamlets" in {
      val ingress = randomStreamlet().asIngress[Foo]
      val processor = randomStreamlet().asProcessor[Foo, Foo]

      val blueprint = connectedBlueprint(ingress, processor)
      blueprint.problems mustBe empty

      val ingressRef = blueprint.streamlets(0)
      val processorRef = blueprint.streamlets(1)

      val fooRemoved = blueprint.remove(ingressRef.name)
      fooRemoved.streamlets(0) mustBe processorRef

      val allRemoved = fooRemoved.remove(processorRef.name)
      allRemoved.connections mustBe empty
      allRemoved.streamlets mustBe empty
      allRemoved.globalProblems mustBe Vector(EmptyStreamlets)
    }

    "remove associated connections when removing a streamlet" in {
      val ingress = randomStreamlet().asIngress[Foo]
      val processor = randomStreamlet().asProcessor[Foo, Foo]
      val egress = randomStreamlet().asEgress[Foo]

      val blueprint = connectedBlueprint(ingress, processor, egress)
      blueprint.problems mustBe empty

      val processorRef = blueprint.streamlets(1)

      val processorRemoved = blueprint.remove(processorRef.name)
      processorRemoved.connections mustBe empty
      processorRemoved.globalProblems must not be empty
    }

    "remove associated connections when removing a streamlet, keep other connections" in {
      val ingress = randomStreamlet().asIngress[Foo]
      val processor = randomStreamlet().asProcessor[Foo, Foo]
      val filterProcessor = randomStreamlet().asProcessor[Foo, Foo]
      val egress = randomStreamlet().asEgress[Foo]

      val blueprint = connectedBlueprint(ingress, processor, filterProcessor, egress)

      blueprint.problems mustBe empty
      val processorRef = blueprint.streamlets(1)
      val filterProcessorRef = blueprint.streamlets(2)
      val egressRef = blueprint.streamlets(3)

      val processorRemoved = blueprint.remove(processorRef.name)

      processorRemoved.connections.size mustBe 1
      processorRemoved.connections.head.from mustBe filterProcessorRef.out
      processorRemoved.connections.head.to mustBe egressRef.in
    }

    "be able to disconnect streamlets" in {
      val ingress = randomStreamlet().asIngress[Foo]
      val processor = randomStreamlet().asProcessor[Foo, Foo]
      val blueprint = connectedBlueprint(ingress, processor)
      blueprint.problems mustBe empty
      val ingressRef = blueprint.streamlets(0)
      val processorRef = blueprint.streamlets(1)

      val fooRemoved = blueprint.remove(ingressRef.name)

      fooRemoved.streamlets(0) mustBe processorRef

      val disconnected = fooRemoved.disconnect(processorRef.name)

      disconnected.connections mustBe empty
      disconnected.problems mustBe Vector(
        UnconnectedInlets(Vector(UnconnectedInlet(processorRef.name, processor.inlets(0))))
      )
    }

    "be able to disconnect from streamlet with multiple inlets using a full port path" in {
      val ingress = randomStreamlet().asIngress[Foo]
      val merge = randomStreamlet().asMerge[Foo, Bar, Foo]
      val blueprint = connectedBlueprint(ingress, merge)
      val mergeRef = blueprint.streamlets(1)
      blueprint.problems.size mustBe 1

      val disconnected = blueprint.disconnect(mergeRef.in0)
      disconnected.connections mustBe empty
      disconnected.problems mustBe Vector(
        UnconnectedInlets(Vector(
          UnconnectedInlet(mergeRef.name, merge.in0),
          UnconnectedInlet(mergeRef.name, merge.in1))
        )
      )
    }

    "be able to disconnect streamlet with one inlet using short name" in {
      val ingress = randomStreamlet().asIngress[Foo]
      val processor = randomStreamlet().asProcessor[Foo, Bar]
      val ingressRef = ingress.ref("foo")
      val processorRef = processor.ref("bar")

      val blueprint = Blueprint()
        .define(Vector(ingress, processor))
        .use(ingressRef)
        .use(processorRef)
        .connect(ingressRef.name, processorRef.name)
      blueprint.problems mustBe empty

      val disconnected = blueprint.disconnect(processorRef.name)
      disconnected.connections mustBe empty
      disconnected.problems mustBe Vector(
        UnconnectedInlets(Vector(UnconnectedInlet(processorRef.name, processor.in)))
      )
    }

    "be able to disconnect streamlet with a short name that is misspelled or missing" in {
      val ingress = randomStreamlet().asIngress[Foo]
      val processor = randomStreamlet().asProcessor[Foo, Foo]
      val blueprint = connectedBlueprint(ingress, processor)
      val ingressRef = blueprint.streamlets(0)
      val existingConnections = blueprint.connections

      val nonExistingBlueprintConnection = blueprint.connect(ingressRef.name, "non-existing-connection")
      nonExistingBlueprintConnection.problems must not be empty

      val nonExistingConnection = nonExistingBlueprintConnection.disconnect("non-existing-connection")
      nonExistingConnection.connections mustBe existingConnections
    }

    "be able to disconnect streamlet with a full port path" in {
      val ingress = randomStreamlet().asIngress[Foo]
      val processor = randomStreamlet().asProcessor[Foo, Foo]
      val blueprint = unconnectedBlueprint(ingress, processor)
      val ingressRef = blueprint.streamlets(0)
      val processorRef = blueprint.streamlets(1)

      val connectedShortOutlet = blueprint.connect(ingressRef.name, processorRef.in)
      connectedShortOutlet.connections must not be empty
      connectedShortOutlet.problems mustBe empty

      val disconnectedShortOutlet = connectedShortOutlet.disconnect(processorRef.name)
      disconnectedShortOutlet.connections mustBe empty
      disconnectedShortOutlet.problems mustBe Vector(
        UnconnectedInlets(Vector(UnconnectedInlet(processorRef.name, processor.inlets(0))))
      )
    }

    "be able to disconnect streamlet that have been connected with a short name for inlet, using a full port part" in {
      val ingress = randomStreamlet().asIngress[Foo]
      val processor = randomStreamlet().asProcessor[Foo, Foo]
      val blueprint = unconnectedBlueprint(ingress, processor)
      val ingressRef = blueprint.streamlets(0)
      val processorRef = blueprint.streamlets(1)

      val connectedShortInlet = blueprint.connect(ingressRef.out, processorRef.name)
      connectedShortInlet.connections must not be empty
      connectedShortInlet.problems mustBe empty

      val disconnectedShortInlet = connectedShortInlet.disconnect(processorRef.in)
      disconnectedShortInlet.connections mustBe empty
      disconnectedShortInlet.problems mustBe Vector(
        UnconnectedInlets(Vector(UnconnectedInlet(processorRef.name, processor.inlets(0))))
      )
    }

    "be able to disconnect streamlet that have been connected with the full port path, using the short name" in {
      val ingress = randomStreamlet().asIngress[Foo]
      val processor = randomStreamlet().asProcessor[Foo, Foo]
      val blueprint = unconnectedBlueprint(ingress, processor)
      val ingressRef = blueprint.streamlets(0)
      val processorRef = blueprint.streamlets(1)

      val connectedLong = blueprint.connect(ingressRef.out, processorRef.in)
      connectedLong.connections must not be empty
      connectedLong.problems mustBe empty

      val disconnectedLong = connectedLong.disconnect(processorRef.name)
      disconnectedLong.problems mustBe Vector(
        UnconnectedInlets(Vector(UnconnectedInlet(processorRef.name, processor.inlets(0))))
      )
    }

    "not create duplicate connections mixing short and full port names" in {
      val ingress = randomStreamlet().asIngress[Foo]
      val processor = randomStreamlet().asProcessor[Foo, Foo]
      val blueprint = connectedBlueprint(ingress, processor)
      blueprint.problems mustBe empty

      val ingressRef = blueprint.streamlets(0)
      val processorRef = blueprint.streamlets(1)

      val shortConnection = StreamletConnection(ingressRef.name, processorRef.name)
      val noDups = blueprint.connect(shortConnection)
      noDups.connections.size mustBe 1
    }

    "not create duplicate connections mixing short and full port names on incompatible connections" in {
      val ingress = randomStreamlet().asIngress[Foo]
      val ingressRef = ingress.randomRef
      val egress = randomStreamlet().asEgress[Bar]
      val egressRef = egress.randomRef

      val blueprint = Blueprint()
        .define(Vector(ingress, egress))
        .use(ingressRef)
        .use(egressRef)
        .connect(ingressRef.out, egressRef.name)

      val noDups = blueprint.connect(ingressRef.out, egressRef.in).verify
      noDups.connections.size mustBe 1
    }
  }

  private def createBlueprintWithConfigurationParameter(parameters: ConfigParameterDescriptor*): Blueprint = {
    val ingress = randomStreamlet().asIngress[Foo]
    val processor = randomStreamlet().asProcessor[Foo, Foo].withConfigParameters(parameters: _*)

    connectedBlueprint(ingress, processor)
  }

  private def createBlueprintWithVolumeMounts(volumeMounts: VolumeMountDescriptor*): Blueprint = {
    val ingress = randomStreamlet().asIngress[Foo]
    val processor = randomStreamlet().asProcessor[Foo, Foo].withVolumeMounts(volumeMounts: _*)

    connectedBlueprint(ingress, processor)
  }
}
