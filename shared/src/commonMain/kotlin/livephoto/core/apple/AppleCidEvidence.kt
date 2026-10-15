package livephoto.core.apple

import livephoto.core.*
import livephoto.core.binary.*

/** Source/generation/role/physical-field bound inspection evidence; never authorization by filename. */
internal object AppleCidEvidence {
    suspend fun parsed(reader: BinaryReader, role: String, value: String, range: ByteRange, budget: ParseBudget): Evidence {
        val identity = reader.identity().orThrow()
        checkedRange(range.offset, range.length, identity.size)
        val digest = Sha256()
        // Length-prefixed raw UTF-16 code units are unambiguous even for opaque provider tokens.
        // The provider identity/generation are hashed, not returned in the description or evidence ID.
        for (field in listOf("apple-cid-inspection-v1", role, identity.id.value, identity.generation.value,
            identity.size.toString(), identity.digest?.value ?: "", value, range.offset.toString(), range.length.toString())) {
            budget.item()
            val length = checkedMultiply(field.length.toULong(), 2uL)
            budget.retain(checkedAdd(checkedMultiply(length, 2uL), 8uL))
            digest.update(unsignedBytes(field.length.toULong(), 8, Endian.Big))
            val bytes = ByteArray(checkedInt(length))
            for (index in field.indices) {
                if (index % 4096 == 0) budget.item()
                bytes[index * 2] = (field[index].code ushr 8).toByte(); bytes[index * 2 + 1] = field[index].code.toByte()
            }
            digest.update(Bytes(bytes))
        }
        reader.validateIdentity().orThrow()
        return Evidence(EvidenceId("apple:cid:$role:${digest.finish().value}"), EvidenceKind.Inspection,
            "Formally parsed Apple $role content identifier in this source generation; does not prove a shared capture",
            Location(source = identity.id, range = range, selector = if (role == "image") "apple:image:content-identifier" else APPLE_CID))
    }
}
