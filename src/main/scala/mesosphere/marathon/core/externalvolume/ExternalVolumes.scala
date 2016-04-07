package mesosphere.marathon.core.externalvolume

import com.wix.accord._
import mesosphere.marathon.core.externalvolume.impl._
import mesosphere.marathon.state._
import org.apache.mesos.Protos.{ ContainerInfo, CommandInfo }

/**
  * Validations for external volumes on different levels.
  */
trait ExternalVolumeValidations {
  def rootGroup: Validator[Group]
  def app: Validator[AppDefinition]
  def volume: Validator[ExternalVolume]
}

/**
  * ExternalVolumeProvider is an interface implemented by external storage volume providers
  */
trait ExternalVolumeProvider {
  def name: String

  def validations: ExternalVolumeValidations

  /** build adds v to the given builder **/
  def build(builder: ContainerInfo.Builder, v: ExternalVolume): Unit
  /** build adds ev to the given builder **/
  def build(containerType: ContainerInfo.Type, builder: CommandInfo.Builder, ev: ExternalVolume): Unit
}

trait ExternalVolumeProviderRegistry {
  /**
    * @return the ExternalVolumeProvider interface registered for the given name
    */
  def get(name: String): Option[ExternalVolumeProvider]

  def all: Iterable[ExternalVolumeProvider]
}

/**
  * API facade for callers interested in storage volumes
  */
object ExternalVolumes {
  private[this] lazy val providers: ExternalVolumeProviderRegistry = StaticExternalVolumeProviderRegistry

  def build(builder: ContainerInfo.Builder, v: ExternalVolume): Unit = {
    providers.get(v.external.provider).foreach { _.build(builder, v) }
  }

  def build(containerType: ContainerInfo.Type, builder: CommandInfo.Builder, v: ExternalVolume): Unit = {
    providers.get(v.external.provider).foreach { _.build(containerType, builder, v) }
  }

  def validExternalVolume: Validator[ExternalVolume] = new Validator[ExternalVolume] {
    def apply(ev: ExternalVolume) = providers.get(ev.external.provider) match {
      case Some(p) => p.validations.volume(ev)
      case None    => Failure(Set(RuleViolation(None, "is unknown provider", Some("external/provider"))))
    }
  }

  /** @return a validator that checks the validity of a container given the related volume providers */
  def validApp(): Validator[AppDefinition] = new Validator[AppDefinition] {
    def apply(app: AppDefinition) = {
      val appProviders = app.externalVolumes.iterator.flatMap(ev => providers.get(ev.external.provider)).toSet
      appProviders.map(_.validations.app).map(validate(app)(_)).fold(Success)(_ and _)
    }
  }

  /** @return a validator that checks the validity of a group given the related volume providers */
  def validRootGroup(): Validator[Group] = new Validator[Group] {
    def apply(grp: Group) =
      providers.all.map(_.validations.rootGroup).map(validate(grp)(_)).fold(Success)(_ and _)
  }
}