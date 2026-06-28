# Source this file before Android builds:
#   source tools/android_env.sh

export JAVA_HOME="${JAVA_HOME:-$HOME/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

path_prepend() {
  case ":$PATH:" in
    *":$1:"*) ;;
    *) PATH="$1:$PATH" ;;
  esac
}

path_prepend "$JAVA_HOME/bin"
path_prepend "$ANDROID_HOME/platform-tools"
path_prepend "$ANDROID_HOME/cmdline-tools/latest/bin"
export PATH

unset -f path_prepend

printf 'Android env loaded: JAVA_HOME=%s ANDROID_HOME=%s\n' "$JAVA_HOME" "$ANDROID_HOME"
