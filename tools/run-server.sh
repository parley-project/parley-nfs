#!/bin/bash
#
# Runs the NFS server from the command line, in the foreground (Ctrl-C stops it).
#
#   tools/run-server.sh [--sudo] [--build] [-Dparley.nfs.name=value ...]
#
#   --sudo    run the server as root, which the operating system needs before the server can change owners
#             (SETATTR of an owner by the mapped root, parley.nfs.enforceModes giving new files to their callers)
#   --build   compile first (mvn -o compile); also done when target/classes is missing
#   -D...     any other setting, passed to the server as a program argument, like the program arguments of an Eclipse launch; a setting also comes from the settings file
#             parley-nfs.properties in Parley's configuration folder, or the file parley.nfs.configFile names
#
# Settings (environment), each used only when set or given here a default:
#   EXPORT_DIR   a folder to share as the export EXPORT_NAME: only when set (and no export is given with -D); without it
#                the exports come from the settings file (parley.nfs.exports, parley.nfs.export.<name>)
#   EXPORT_NAME  its name; the client mounts server:/<name>             (default docs, with EXPORT_DIR)
#   IDMAP        file of uid.<number>=<user name> lines                 (parley.nfs.idmap)
#   STATE_DIR    folder that keeps identities, lists and delegations    (parley.nfs.stateDir)
#   LOCKDIR      parley.files.lockDirectory
#   JAVA         the java command                                       (default: java on the PATH)
#
# Needs the parley jars in ~/.m2 (parley-net, -iam, -files, -core, -io) installed from release-2.0.0.
# The classpath is worked out once with maven and kept in target/classpath.txt.
#
# Examples:
#   tools/run-server.sh
#   IDMAP=/Volumes/Data/nfs-idmap.properties tools/run-server.sh --sudo -Dparley.nfs.enforceModes=true

: "${EXPORT_NAME:=docs}"
: "${JAVA:=java}"

REPO=$(cd "$(dirname "$0")/.." && pwd)
SUDO=""
BUILD=false
PROPS=()
for arg in "$@"; do
	case "$arg" in
		--sudo) SUDO="sudo" ;;
		--build) BUILD=true ;;
		-D*) PROPS+=("$arg") ;;
		-h|--help) sed -n '3,26p' "$0"; exit 0 ;;
		*) echo "run-server.sh: unknown argument $arg (see --help)" >&2; exit 2 ;;
	esac
done

cd "$REPO" || exit 1
if $BUILD || [ ! -d target/classes ]; then
	mvn -o -q compile || exit 1
fi
CPFILE=target/classpath.txt
if [ ! -s "$CPFILE" ] || [ pom.xml -nt "$CPFILE" ]; then
	mvn -o -q dependency:build-classpath -Dmdep.outputFile="$CPFILE" || exit 1
fi
CP="$REPO/target/classes:$(cat "$CPFILE")"

# a setting given with -D wins over the ones made from the environment
have() { local p; for p in "${PROPS[@]}"; do case "$p" in -D$1=*) return 0 ;; esac; done; return 1; }
OPTS=()
# the export defaults only apply when no export setting is given here (an export given here, one or several, is used as it is)
EXPORTS_GIVEN=false
for p in "${PROPS[@]}"; do case "$p" in -Dparley.nfs.export=*|-Dparley.nfs.exports=*|-Dparley.nfs.export.*) EXPORTS_GIVEN=true ;; esac; done
if [ -n "$EXPORT_DIR" ] && ! $EXPORTS_GIVEN; then
	OPTS+=("-Dparley.nfs.exports=$EXPORT_NAME" "-Dparley.nfs.export.$EXPORT_NAME=$EXPORT_DIR")
fi
[ -n "$IDMAP" ] && ! have parley.nfs.idmap && OPTS+=("-Dparley.nfs.idmap=$IDMAP")
[ -n "$STATE_DIR" ] && ! have parley.nfs.stateDir && OPTS+=("-Dparley.nfs.stateDir=$STATE_DIR")
[ -n "$LOCKDIR" ] && ! have parley.files.lockDirectory && OPTS+=("-Dparley.files.lockDirectory=$LOCKDIR")

if [ -n "$SUDO" ]; then
	JAVA=$(command -v "$JAVA") || { echo "run-server.sh: java not found" >&2; exit 1; }
fi

[ -n "$EXPORT_DIR" ] && ! $EXPORTS_GIVEN && echo "NFS server: export $EXPORT_NAME = $EXPORT_DIR${SUDO:+ (as root)}"
# settings go after the class name as program args, as in an Eclipse launch: the server lists them at start
# and they win over the settings file and over the JVM's own -D options
exec $SUDO "$JAVA" -cp "$CP" us.bringardner.parley.nfs.server.NfsServer "${OPTS[@]}" "${PROPS[@]}"
