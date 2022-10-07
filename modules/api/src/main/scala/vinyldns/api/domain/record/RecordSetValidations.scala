/*
 * Copyright 2018 Comcast Cable Communications Management, LLC
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

package vinyldns.api.domain.record

import cats.syntax.either._
import vinyldns.api.Interfaces._
import vinyldns.api.backend.dns.DnsConversions
import vinyldns.api.config.HighValueDomainConfig
import vinyldns.api.domain._
import vinyldns.core.domain.DomainHelpers._
import vinyldns.core.domain.record.RecordType._
import vinyldns.api.domain.zone._
import vinyldns.core.domain.auth.AuthPrincipal
import vinyldns.core.domain.membership.Group
import vinyldns.core.domain.record.{RecordSet, RecordType}
import vinyldns.core.domain.zone.Zone
import vinyldns.core.Messages._

import scala.util.matching.Regex

object RecordSetValidations {

  def validRecordTypes(recordSet: RecordSet, zone: Zone): Either[Throwable, Unit] =
    recordSet.typ match {
      case CNAME | SOA | TXT | NS | DS => ().asRight
      case PTR =>
        ensuring(InvalidRequest("PTR is not valid in forward lookup zone"))(zone.isReverse)
      case _ =>
        ensuring(InvalidRequest(s"${recordSet.typ} is not valid in reverse lookup zone."))(
          !zone.isReverse
        )
    }

  def validRecordNameLength(recordSet: RecordSet, zone: Zone): Either[Throwable, Unit] = {
    val absoluteName = recordSet.name + "." + zone.name
    ensuring(InvalidRequest(s"record set name ${recordSet.name} is too long")) {
      absoluteName.length < 256 || isOriginRecord(recordSet.name, zone.name)
    }
  }

  def notPending(recordSet: RecordSet): Either[Throwable, Unit] =
    ensuring(
      PendingUpdateError(
        s"RecordSet with id ${recordSet.id}, name ${recordSet.name} and type ${recordSet.typ} " +
          s"currently has a pending change"
      )
    )(
      !recordSet.isPending
    )

  def noCnameWithNewName(
      newRecordSet: RecordSet,
      existingRecordsWithName: List[RecordSet],
      zone: Zone
  ): Either[Throwable, Unit] =
    ensuring(
      RecordSetAlreadyExists(
        s"RecordSet with name ${newRecordSet.name} and type CNAME already " +
          s"exists in zone ${zone.name}"
      )
    )(
      !existingRecordsWithName.exists(rs => rs.id != newRecordSet.id && rs.typ == CNAME)
    )

  def recordSetDoesNotExist(
      newRecordSet: RecordSet,
      existingRecordsWithName: List[RecordSet],
      zone: Zone
  ): Either[Throwable, Unit] =
    ensuring(
      RecordSetAlreadyExists(
        s"RecordSet with name ${newRecordSet.name} and type ${newRecordSet.typ} already " +
          s"exists in zone ${zone.name}"
      )
    )(
      !existingRecordsWithName.exists(rs => rs.id != newRecordSet.id && rs.typ == newRecordSet.typ)
    )

  // Check whether the record has dot or not
  def checkForDot(
      newRecordSet: RecordSet,
      zone: Zone,
      existingRecordSet: Option[RecordSet] = None,
      recordFqdnDoesNotExist: Boolean,
      dottedHostZoneConfig: Set[String],
      isRecordTypeAndUserAllowed: Boolean
  ): Either[Throwable, Unit] = {

    val zoneName = if(zone.name.takeRight(1) != ".") zone.name + "." else zone.name
    // Check if the zone of the recordset is present in dotted hosts config list
    val isDomainAllowed = dottedHostZoneConfig.contains(zoneName)

    // Check if record set contains dot and if it is in zone which is allowed to have dotted records from dotted hosts config
    if(newRecordSet.name.contains(".") && isDomainAllowed && newRecordSet.name != zone.name) {
      if(!isRecordTypeAndUserAllowed){
        isUserAndRecordTypeAuthorized(newRecordSet, zone, existingRecordSet, recordFqdnDoesNotExist, isRecordTypeAndUserAllowed)
      }
      else {
        isDotted(newRecordSet, zone, existingRecordSet, recordFqdnDoesNotExist, isRecordTypeAndUserAllowed)
      }
    }
    else {
      isNotDotted(newRecordSet, zone, existingRecordSet)
    }
  }

  // For dotted host. Check if a record is already present which conflicts with the new dotted record. If so, throw an error
  def isDotted(
     newRecordSet: RecordSet,
     zone: Zone,
     existingRecordSet: Option[RecordSet] = None,
     recordFqdnDoesNotExist: Boolean,
     isRecordTypeAndUserAllowed: Boolean
  ): Either[Throwable, Unit] =
    ensuring(
      InvalidRequest(
        s"Record with fqdn '${newRecordSet.name}.${zone.name}' cannot be created. " +
          s"Please check if a record with the same FQDN and type already exist and make the change there."
      )
    )(
      (newRecordSet.name != zone.name || existingRecordSet.exists(_.name == newRecordSet.name)) && recordFqdnDoesNotExist && isRecordTypeAndUserAllowed
    )

  // For dotted host. Check if the user is authorized and the record type is allowed. If not, throw an error
  def isUserAndRecordTypeAuthorized(
     newRecordSet: RecordSet,
     zone: Zone,
     existingRecordSet: Option[RecordSet] = None,
     recordFqdnDoesNotExist: Boolean,
     isRecordTypeAndUserAllowed: Boolean
  ): Either[Throwable, Unit] =
    ensuring(
      InvalidRequest(
        s"Record type is not allowed or the user is not authorized to create a dotted host in the zone '${zone.name}'"
      )
    )(
      (newRecordSet.name != zone.name || existingRecordSet.exists(_.name == newRecordSet.name)) && recordFqdnDoesNotExist && isRecordTypeAndUserAllowed
    )

  // Check if the recordset contains dot but is not in the allowed zones to create dotted records. If so, throw an error
  def isNotDotted(
      newRecordSet: RecordSet,
      zone: Zone,
      existingRecordSet: Option[RecordSet] = None
  ): Either[Throwable, Unit] =
    ensuring(
      InvalidRequest(
        s"Record with name ${newRecordSet.name} and type ${newRecordSet.typ} is a dotted host which" +
          s" is not allowed in zone ${zone.name}"
      )
    )(
      newRecordSet.name == zone.name || !newRecordSet.name.contains(".") ||
        existingRecordSet.exists(_.name == newRecordSet.name)
    )

  def typeSpecificValidations(
      newRecordSet: RecordSet,
      existingRecordsWithName: List[RecordSet],
      zone: Zone,
      existingRecordSet: Option[RecordSet],
      approvedNameServers: List[Regex],
      recordFqdnDoesNotExist: Boolean,
      dottedHostZoneConfig: Set[String],
      isRecordTypeAndUserAllowed: Boolean
  ): Either[Throwable, Unit] =
    newRecordSet.typ match {
      case CNAME => cnameValidations(newRecordSet, existingRecordsWithName, zone, existingRecordSet, recordFqdnDoesNotExist, dottedHostZoneConfig, isRecordTypeAndUserAllowed)
      case NS => nsValidations(newRecordSet, zone, existingRecordSet, approvedNameServers, recordFqdnDoesNotExist, dottedHostZoneConfig, isRecordTypeAndUserAllowed)
      case SOA => soaValidations(newRecordSet, zone, recordFqdnDoesNotExist, dottedHostZoneConfig, isRecordTypeAndUserAllowed)
      case PTR => ptrValidations(newRecordSet, zone)
      case SRV | TXT | NAPTR => ().asRight // SRV, TXT and NAPTR do not go through dotted host check
      case DS => dsValidations(newRecordSet, existingRecordsWithName, zone, recordFqdnDoesNotExist, dottedHostZoneConfig, isRecordTypeAndUserAllowed)
      case _ => checkForDot(newRecordSet, zone, existingRecordSet, recordFqdnDoesNotExist, dottedHostZoneConfig, isRecordTypeAndUserAllowed)
    }

  def typeSpecificDeleteValidations(recordSet: RecordSet, zone: Zone): Either[Throwable, Unit] =
    // for delete, the only validation is that you cant remove an NS at origin
    recordSet.typ match {
      case NS =>
        isNotOrigin(
          recordSet,
          zone,
          s"Record with name ${recordSet.name} is an NS record at apex and cannot be edited"
        )
      case SOA => InvalidRequest("SOA records cannot be deleted").asLeft
      case _ => ().asRight
    }

  /* Add/update validations by record type */
  def cnameValidations(
      newRecordSet: RecordSet,
      existingRecordsWithName: List[RecordSet],
      zone: Zone,
      existingRecordSet: Option[RecordSet] = None,
      recordFqdnDoesNotExist: Boolean,
      dottedHostZoneConfig: Set[String],
      isRecordTypeAndUserAllowed: Boolean
  ): Either[Throwable, Unit] = {
    // cannot create a cname record if a record with the same exists
    val noRecordWithName = {
      ensuring(
        RecordSetAlreadyExists(
          s"RecordSet with name ${newRecordSet.name} already " +
            s"exists in zone ${zone.name}, CNAME record cannot use duplicate name"
        )
      )(
        existingRecordsWithName.forall(_.id == newRecordSet.id)
      )
    }

    // cname recordset data cannot contain more than one sequential '.'
    val RDataWithConsecutiveDots = {
      ensuring(
        RecordSetValidation(
          s"RecordSet Data cannot contain consecutive 'dot' character. RData: '${newRecordSet.records.head.toString}'"
        )
      )(
        noConsecutiveDots(newRecordSet.records.head.toString)
      )
    }

    for {
      _ <- isNotOrigin(
        newRecordSet,
        zone,
        "CNAME RecordSet cannot have name '@' because it points to zone origin"
      )
      _ <- noRecordWithName
      _ <- RDataWithConsecutiveDots
      _ <- checkForDot(newRecordSet, zone, existingRecordSet, recordFqdnDoesNotExist, dottedHostZoneConfig, isRecordTypeAndUserAllowed)
    } yield ()

  }

  def dsValidations(
      newRecordSet: RecordSet,
      existingRecordsWithName: List[RecordSet],
      zone: Zone,
      recordFqdnDoesNotExist: Boolean,
      dottedHostZoneConfig: Set[String],
      isRecordTypeAndUserAllowed: Boolean
  ): Either[Throwable, Unit] = {
    // see https://tools.ietf.org/html/rfc4035#section-2.4
    val nsChecks = existingRecordsWithName.find(_.typ == NS) match {
      case Some(_) => ().asRight
      case None =>
        InvalidRequest(
          s"DS record [${newRecordSet.name}] is invalid because there is no NS record with that " +
            s"name in the zone [${zone.name}]"
        ).asLeft
    }

    for {
      _ <- checkForDot(newRecordSet, zone, None, recordFqdnDoesNotExist, dottedHostZoneConfig, isRecordTypeAndUserAllowed)
      _ <- isNotOrigin(
        newRecordSet,
        zone,
        s"Record with name [${newRecordSet.name}] is an DS record at apex and cannot be added"
      )
      _ <- nsChecks
    } yield ()
  }

  def nsValidations(
      newRecordSet: RecordSet,
      zone: Zone,
      oldRecordSet: Option[RecordSet],
      approvedNameServers: List[Regex],
      recordFqdnDoesNotExist: Boolean,
      dottedHostZoneConfig: Set[String],
      isRecordTypeAndUserAllowed: Boolean
  ): Either[Throwable, Unit] = {
    // TODO kept consistency with old validation. Not sure why NS could be dotted in reverse specifically
    val isNotDottedHost = if (!zone.isReverse) checkForDot(newRecordSet, zone, None, recordFqdnDoesNotExist, dottedHostZoneConfig, isRecordTypeAndUserAllowed) else ().asRight

    for {
      _ <- isNotDottedHost
      _ <- isNotOrigin(
        newRecordSet,
        zone,
        s"Record with name ${newRecordSet.name} is an NS record at apex and cannot be added"
      )
      _ <- containsApprovedNameServers(newRecordSet, approvedNameServers)
      _ <- oldRecordSet
        .map { rs =>
          isNotOrigin(
            rs,
            zone,
            s"Record with name ${newRecordSet.name} is an NS record at apex and cannot be edited"
          )
        }
        .getOrElse(().asRight)
    } yield ()
  }

  def soaValidations(newRecordSet: RecordSet, zone: Zone, recordFqdnDoesNotExist: Boolean, dottedHostZoneConfig: Set[String], isRecordTypeAndUserAllowed: Boolean): Either[Throwable, Unit] =
    // TODO kept consistency with old validation. in theory if SOA always == zone name, no special case is needed here
    if (!zone.isReverse) checkForDot(newRecordSet, zone, None, recordFqdnDoesNotExist, dottedHostZoneConfig, isRecordTypeAndUserAllowed) else ().asRight

  def ptrValidations(newRecordSet: RecordSet, zone: Zone): Either[Throwable, Unit] =
    // TODO we don't check for PTR as dotted...not sure why
    ReverseZoneHelpers.ptrIsInClasslessDelegatedZone(zone, newRecordSet.name).map(_ => ())

  private def isNotOrigin(recordSet: RecordSet, zone: Zone, err: String): Either[Throwable, Unit] =
    ensuring(InvalidRequest(err))(
      !isOriginRecord(recordSet.name, omitTrailingDot(zone.name))
    )

  private def containsApprovedNameServers(
      nsRecordSet: RecordSet,
      approvedNameServers: List[Regex]
  ): Either[Throwable, Unit] =
    ZoneRecordValidations
      .containsApprovedNameServers(approvedNameServers, nsRecordSet)
      .toEither
      .map(_ => ())
      .leftMap(errors => InvalidRequest(errors.toList.mkString(", ")))

  private def isOriginRecord(recordSetName: String, zoneName: String): Boolean =
    recordSetName == "@" || omitTrailingDot(recordSetName) == omitTrailingDot(zoneName)

  def isNotHighValueDomain(
      recordSet: RecordSet,
      zone: Zone,
      highValueDomainConfig: HighValueDomainConfig
  ): Either[Throwable, Unit] = {
    val result = recordSet.typ match {
      case RecordType.PTR =>
        val ip = ReverseZoneHelpers.reverseNameToIp(recordSet.name, zone)
        ZoneRecordValidations.isNotHighValueIp(highValueDomainConfig.ipList, ip)
      case _ =>
        val fqdn = DnsConversions.recordDnsName(recordSet.name, zone.name).toString()
        ZoneRecordValidations.isNotHighValueFqdn(highValueDomainConfig.fqdnRegexes, fqdn)
    }

    result.toEither
      .map(_ => ())
      .leftMap(errors => InvalidRequest(errors.toList.map(_.message).mkString(", ")))
  }

  def checkAllowedDots(allowedDotsLimit: Int, recordSet: RecordSet, zone: Zone): Either[Throwable, Unit] = {
    ensuring(
      InvalidRequest(
        s"RecordSet with name ${recordSet.name} has more dots than that is allowed in config for this zone " +
          s"which is, 'allowed-dots-limit = $allowedDotsLimit'."
      )
    )(
      recordSet.name.count(_ == '.') <= allowedDotsLimit || (recordSet.name.count(_ == '.') == 1 &&
        recordSet.name.takeRight(1) == ".") || recordSet.name == zone.name ||
        (recordSet.typ.toString == "PTR" || recordSet.typ.toString == "SRV" || recordSet.typ.toString == "TXT" || recordSet.typ.toString == "NAPTR")
    )
  }

  def canUseOwnerGroup(
      ownerGroupId: Option[String],
      group: Option[Group],
      authPrincipal: AuthPrincipal
  ): Either[Throwable, Unit] =
    (ownerGroupId, group) match {
      case (None, _) => ().asRight
      case (Some(groupId), None) =>
        InvalidGroupError(s"""Record owner group with id "$groupId" not found""").asLeft
      case (Some(groupId), Some(_)) =>
        if (authPrincipal.isSuper || authPrincipal.isGroupMember(groupId)) ().asRight
        else InvalidRequest(s"""User not in record owner group with id "$groupId"""").asLeft
    }

  def unchangedRecordName(
      existing: RecordSet,
      updates: RecordSet,
      zone: Zone
  ): Either[Throwable, Unit] = Either.cond(
    updates.name.toLowerCase == existing.name.toLowerCase
      || (updates.name == "@" && existing.name.toLowerCase == zone.name.toLowerCase),
    (),
    InvalidRequest("Cannot update RecordSet's name.")
  )

  def unchangedRecordType(
      existing: RecordSet,
      updates: RecordSet
  ): Either[Throwable, Unit] =
    Either.cond(
      updates.typ == existing.typ,
      (),
      InvalidRequest("Cannot update RecordSet's record type.")
    )

  def unchangedZoneId(
      existing: RecordSet,
      updates: RecordSet
  ): Either[Throwable, Unit] =
    Either.cond(
      updates.zoneId == existing.zoneId,
      (),
      InvalidRequest("Cannot update RecordSet's zone ID.")
    )

  def validRecordNameFilterLength(recordNameFilter: String): Either[Throwable, Unit] =
    ensuring(onError = InvalidRequest(RecordNameFilterError)) {
      val searchRegex = "[a-zA-Z0-9].*[a-zA-Z0-9]+".r
      val wildcardRegex = raw"^\s*[*%].*[*%]\s*$$".r
      searchRegex.findFirstIn(recordNameFilter).isDefined && wildcardRegex.findFirstIn(recordNameFilter).isEmpty
    }
}
