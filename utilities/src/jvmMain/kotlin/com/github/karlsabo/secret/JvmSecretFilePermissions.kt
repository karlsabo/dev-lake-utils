package com.github.karlsabo.secret

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType.ALLOW
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet

internal interface JvmSecretFilePermissions {
    fun create(path: Path, content: ByteArray)

    fun restrict(path: Path)

    fun verify(path: Path)
}

internal fun createJvmSecretFilePermissions(
    supportedViews: Set<String> = FileSystems.getDefault().supportedFileAttributeViews(),
    providerLabel: String = "secret",
): JvmSecretFilePermissions = when {
    "posix" in supportedViews -> PosixSecretFilePermissions(providerLabel)

    "acl" in supportedViews -> DefaultAclSecretFilePermissions(providerLabel)

    else -> throw SecretFileWriteException(
        "Cannot securely write $providerLabel files: the file system supports neither POSIX permissions nor ACLs",
    )
}

private class PosixSecretFilePermissions(
    private val providerLabel: String,
) : JvmSecretFilePermissions {
    override fun create(path: Path, content: ByteArray) {
        val permissions = PosixFilePermissions.asFileAttribute(OWNER_ONLY_POSIX_PERMISSIONS)
        Files.newByteChannel(path, setOf(CREATE_NEW, WRITE), permissions).use { channel ->
            channel.writeAll(content)
        }
        restrict(path)
    }

    override fun restrict(path: Path) {
        Files.setPosixFilePermissions(path, OWNER_ONLY_POSIX_PERMISSIONS)
    }

    override fun verify(path: Path) {
        if (Files.getPosixFilePermissions(path) != OWNER_ONLY_POSIX_PERMISSIONS) {
            throwOwnerOnlyPermissionFailure(path, providerLabel)
        }
    }
}

private class DefaultAclSecretFilePermissions(
    private val providerLabel: String,
) : JvmSecretFilePermissions {
    override fun create(path: Path, content: ByteArray) {
        Files.newByteChannel(path, setOf(CREATE_NEW, WRITE)).use { }
        restrict(path)
        verify(path)
        Files.newByteChannel(path, setOf(WRITE)).use { channel ->
            channel.writeAll(content)
        }
    }

    override fun restrict(path: Path) {
        val view = path.aclView(providerLabel)
        val ownerEntry = AclEntry.newBuilder()
            .setType(ALLOW)
            .setPrincipal(view.owner)
            .setPermissions(OWNER_ONLY_ACL_PERMISSIONS)
            .build()
        view.acl = listOf(ownerEntry)
    }

    override fun verify(path: Path) {
        val view = path.aclView(providerLabel)
        val owner = view.owner
        val entries = view.acl
        val ownerAllowedPermissions = entries
            .filter { entry -> entry.type() == ALLOW && entry.principal() == owner }
            .flatMapTo(mutableSetOf(), AclEntry::permissions)
        val grantsAnotherPrincipal = entries.any { entry ->
            entry.type() == ALLOW && entry.principal() != owner && entry.permissions().isNotEmpty()
        }
        val deniesOwner = entries.any { entry ->
            entry.type() != ALLOW && entry.principal() == owner && entry.permissions().isNotEmpty()
        }
        if (grantsAnotherPrincipal || deniesOwner || !ownerAllowedPermissions.containsAll(OWNER_ONLY_ACL_PERMISSIONS)) {
            throwOwnerOnlyPermissionFailure(path, providerLabel)
        }
    }
}

private fun Path.aclView(providerLabel: String): AclFileAttributeView {
    val view = Files.getFileAttributeView(this, AclFileAttributeView::class.java)
    if (view == null) throwOwnerOnlyPermissionFailure(this, providerLabel)
    return view
}

private fun throwOwnerOnlyPermissionFailure(path: Path, providerLabel: String): Nothing {
    val message = "$providerLabel file permissions could not be restricted to its owner: $path"
    throw SecretFileWriteException(message)
}

private val OWNER_ONLY_POSIX_PERMISSIONS = setOf(OWNER_READ, OWNER_WRITE)
private val OWNER_ONLY_ACL_PERMISSIONS: Set<AclEntryPermission> = EnumSet.allOf(AclEntryPermission::class.java)

private fun SeekableByteChannel.writeAll(content: ByteArray) {
    val buffer = ByteBuffer.wrap(content)
    while (buffer.hasRemaining()) write(buffer)
}
