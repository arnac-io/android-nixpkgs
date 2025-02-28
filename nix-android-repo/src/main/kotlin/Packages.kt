package codes.tad.nixandroidrepo

import com.android.repository.api.Checksum
import com.android.repository.api.License
import com.android.repository.api.RemotePackage
import com.android.repository.api.RepoPackage
import com.android.repository.impl.meta.Archive
import com.android.repository.impl.meta.RemotePackageImpl
import com.android.repository.util.InstallerUtil
import com.android.sdklib.repository.meta.DetailsTypes
import java.io.File

interface NixExpr {
    fun nix(): String
}

fun <T : NixExpr> List<T>.nixSet(keySelector: (T) -> String): String {
    return joinToString("\n", prefix = "{\n", postfix = "\n}") { expr ->
        "%s = %s;".format(keySelector(expr), expr.nix()).prependIndent("⇥")
    }
}

data class Repo(
    val packages: List<Package>,
    val licenses: List<AndroidLicense>
) : NixExpr {
    override fun nix(): String {
        return """
            # Generated by nix-android-repo

            { mkBuildTools
            , mkCmdlineTools
            , mkEmulator
            , mkNdk
            , mkNdkBundle
            , mkPlatformTools
            , mkPrebuilt
            , mkTools
            , mkSrcOnly
            }: %s
        """.trimIndent().format(
            packages.nixSet { it.path.joinToString("-") }
        )
    }
}

data class Package(
    val id: String,
    val path: List<String>,
    val pname: String,
    val version: String,
    val builder: String,
    val sources: List<Source>,
    val displayName: String,
    val packageDir: String,
    val license: AndroidLicense
) : NixExpr {
    override fun nix(): String {
        return """
        $builder {
        ⇥id = "$id";
        ⇥pname = "$pname";
        ⇥version = "$version";
        ⇥sources = %s;
        ⇥displayName = "$displayName";
        ⇥path = "$packageDir";
        ⇥license = %s;
        ⇥xml = ./$pname.xml;
        }
    """.trimIndent().format(sources.nixSet { it.platform }.indentMiddle(), license.nix().indentMiddle())
    }
}

data class Source(
    val platform: String,
    val url: String,
    val checksum: Checksum
) : NixExpr {
    override fun nix(): String {
        return """
        {
        ⇥url = "$url";
        ⇥${checksum.nixAttr()};
        }
    """.trimIndent()
    }
}

class AndroidLicense(private val license: License) : NixExpr {
    override fun nix(): String {
        return """
            {
            ⇥id = "${license.id}";
            ⇥hash = "${license.licenseHash}";
            }
        """.trimIndent()
    }
}

fun Checksum.nixAttr(): String = buildString {
    append(when (this@nixAttr.type) {
        "sha1" -> "sha1"
        "sha-1" -> "sha1"
        "sha256" -> "sha256"
        "sha-256" -> "sha256"
        "sha512" -> "sha512"
        "sha-512" -> "sha512"
        else -> error("Unknown checksum type: ${this@nixAttr.type}")
    })
    append(" = \"")
    append(value)
    append("\"")
}

fun Archive.platform(): String {
    val os = when (hostOs) {
        "linux" -> "linux"
        "macosx" -> "darwin"
        "windows" -> "windows"
        null -> null
        else -> error("Unknown os: $hostOs")
    }
    val arch = when (hostArch) {
        "x86" -> "i686"
        "x64" -> "x86_64"
        "aarch64" -> "aarch64"
        null -> null
        else -> error("Unknown arch: $hostArch")
    }
    return when {
        os != null -> (if (arch != null) "$arch-" else "") + os
        arch != null -> arch
        else -> "all"
    }
}

fun RemotePackage.attrpath(): List<String> {
    return path.split(";").fold(mutableListOf()) { acc, p ->
        val sanitized = p.sanitize()
        if (acc.isNotEmpty() && (sanitized.first().isDigit() || sanitized == "latest")) {
            acc.drop(1) + "${acc.last()}-$sanitized"
        } else {
            acc + sanitized
        } as MutableList<String>
    }
}

fun RemotePackage.revision(): String {
    return version.toShortString().replace(" ", "-")
}

fun String.indentMiddle(): String {
    val lines = lines()
    return lines().mapIndexed { i, line ->
        if (i == 0 || i == lines.size) {
            line
        } else {
            if (line.isBlank()) "" else "⇥$line"
        }
    }.joinToString("\n")
}

fun String.formatIndents(size: Int = 2) = replace("⇥", " ".repeat(size))

fun RemotePackage.builder(): String {
    return when (typeDetails) {
        is DetailsTypes.SourceDetailsType,
        is DetailsTypes.PlatformDetailsType,
        is DetailsTypes.ExtraDetailsType,
        is DetailsTypes.AddonDetailsType,
        is DetailsTypes.MavenType,
        is DetailsTypes.SysImgDetailsType -> "mkSrcOnly"

        else -> when (path.split(";").first()) {
            "build-tools" -> "mkBuildTools"
            "cmdline-tools" -> "mkCmdlineTools"
            "emulator" -> "mkEmulator"
            "ndk", "ndk-bundle" -> "mkNdk"
            "platform-tools" -> "mkPlatformTools"
            "tools" -> "mkTools"
            "cmake", "skiaparser" -> "mkPrebuilt"
            else -> "mkSrcOnly"
        }
    }
}

fun nixPackages(packages: Map<String, RemotePackage>): List<Package> {
    return packages.values
        .sortedBy { it.path }
        .filterIsInstance<RemotePackageImpl>()
        .map { pkg ->
            Package(
                id = pkg.path,
                path = pkg.attrpath(),
                pname = pkg.path.sanitize(),
                version = pkg.revision(),
                builder = pkg.builder(),
                sources = pkg.allArchives.map { archive ->
                    println("${pkg.path}-${pkg.revision()}: ${archive.complete.url}")
                    Source(
                        platform = archive.platform(),
                        url = InstallerUtil.resolveUrl(archive.complete.url, pkg, NixProgressIndicator)!!.toString(),
                        checksum = archive.complete.typedChecksum
                    )
                },
                displayName = pkg.displayName,
                packageDir = pkg.path.replace(RepoPackage.PATH_SEPARATOR, File.separatorChar),
                license = AndroidLicense(pkg.license)
            )
        }
}

fun nixRepo(packages: Map<String, RemotePackage>): Repo {
    return Repo(
        packages = nixPackages(packages),
        licenses = packages.values.map { it.license }
            .distinctBy { it.id }
            .sortedBy { it.id }
            .map { AndroidLicense(it) }
    )
}

private val symbols = Regex("""[ _.;]""")

fun String.sanitize(): String = symbols.replace(this, "-")
