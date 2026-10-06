package com.sbk.optionspricer.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.*;
import java.util.Collections;
import java.util.List;

public class MmapSecurityUtils {
    public static void secureMmapFile(Path path) throws IOException {
        AclFileAttributeView aclView = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (aclView != null) {
            UserPrincipal owner = aclView.getOwner();
            AclEntry entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(
                            AclEntryPermission.READ_DATA,
                            AclEntryPermission.WRITE_DATA,
                            AclEntryPermission.APPEND_DATA,
                            AclEntryPermission.READ_NAMED_ATTRS,
                            AclEntryPermission.WRITE_NAMED_ATTRS,
                            AclEntryPermission.EXECUTE,
                            AclEntryPermission.READ_ATTRIBUTES,
                            AclEntryPermission.WRITE_ATTRIBUTES,
                            AclEntryPermission.DELETE,
                            AclEntryPermission.READ_ACL,
                            AclEntryPermission.SYNCHRONIZE
                    )
                    .build();
            aclView.setAcl(Collections.singletonList(entry));
        } else {
            PosixFileAttributeView posixView = Files.getFileAttributeView(path, PosixFileAttributeView.class);
            if (posixView != null) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            }
        }
    }

    public static Path validateMmapPath(String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            return Path.of("data/shm_state.dat");
        }
        if (pathStr.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Mmap state file path contains null byte");
        }
        return Path.of(pathStr).normalize();
    }
}
