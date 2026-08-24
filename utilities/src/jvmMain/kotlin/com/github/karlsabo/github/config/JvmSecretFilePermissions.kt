package com.github.karlsabo.github.config

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
    fun create(path: java.nio.file.Path, content: ByteArray)

    fun restrict(path: java.nio.file.Path)

    fun verify(path: java.nio.file.Path)
}

internal fun createJvmSecretFilePermissions(
    supportedViews: Set<String> = FileSystems.getDefault().supportedFileAttributeViews(),
): JvmSecretFilePermissions = when {
    "posix" in supportedViews -> PosixSecretFilePermissions

    "acl" in supportedViews -> DefaultAclSecretFilePermissions

    else -> throw GitHubSecretWriteException(
        "Cannot securely write GitHub secret files: the file system supports neither POSIX permissions nor ACLs",
    )
}

private object PosixSecretFilePermissions : JvmSecretFilePermissions {
    override fun create(path: java.nio.file.Path, content: ByteArray) {
        val permissions = PosixFilePermissions.asFileAttribute(OWNER_ONLY_POSIX_PERMISSIONS)
        Files.newByteChannel(path, setOf(CREATE_NEW, WRITE), permissions).use { channel ->
            channel.writeAll(content)
        }
        restrict(path)
    }

    override fun restrict(path: java.nio.file.Path) {
        Files.setPosixFilePermissions(path, OWNER_ONLY_POSIX_PERMISSIONS)
    }

    override fun verify(path: java.nio.file.Path) {
        if (Files.getPosixFilePermissions(path) != OWNER_ONLY_POSIX_PERMISSIONS) {
            throwOwnerOnlyPermissionFailure(path)
        }
    }
}

private object DefaultAclSecretFilePermissions : JvmSecretFilePermissions {
    override fun create(path: java.nio.file.Path, content: ByteArray) {
        Files.newByteChannel(path, setOf(CREATE_NEW, WRITE)).use { }
        restrict(path)
        verify(path)
        Files.newByteChannel(path, setOf(WRITE)).use { channel ->
            channel.writeAll(content)
        }
    }

    override fun restrict(path: java.nio.file.Path) {
        val view = path.aclView()
        val ownerEntry = AclEntry.newBuilder()
            .setType(ALLOW)
            .setPrincipal(view.owner)
            .setPermissions(OWNER_ONLY_ACL_PERMISSIONS)
            .build()
        view.acl = listOf(ownerEntry)
    }

    override fun verify(path: java.nio.file.Path) {
        val view = path.aclView()
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
            throwOwnerOnlyPermissionFailure(path)
        }
    }
}

private fun Path.aclView(): AclFileAttributeView = Files.getFileAttributeView(this, AclFileAttributeView::class.java)
    ?: throwOwnerOnlyPermissionFailure(this)

private fun throwOwnerOnlyPermissionFailure(path: java.nio.file.Path): Nothing = throw GitHubSecretWriteException(
    "GitHub secret file permissions could not be restricted to its owner: $path",
)

private val OWNER_ONLY_POSIX_PERMISSIONS = setOf(OWNER_READ, OWNER_WRITE)
private val OWNER_ONLY_ACL_PERMISSIONS: Set<AclEntryPermission> = EnumSet.allOf(AclEntryPermission::class.java)

private fun SeekableByteChannel.writeAll(content: ByteArray) {
    val buffer = ByteBuffer.wrap(content)
    while (buffer.hasRemaining()) write(buffer)
}
