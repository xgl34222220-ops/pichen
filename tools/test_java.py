#!/usr/bin/env python3
"""Run tests after the Android build, or protocol-only with any JDK 8+ offline."""
import argparse
import os
import subprocess
from build_app import ROOT, LOCAL_TOOLS, BUILD, sdk_paths, java_tool

PROTOCOL_TESTS = ('DnsPacketTest', 'DnsCacheTest', 'DnsUpstreamTest', 'DnsResponseFilterTest', 'RuleProfilesTest', 'NetworkEpochTest', 'RuleUpdateGateTest', 'ModuleArchiveTest')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol-only', action='store_true', help='No Android SDK, root, or external network required')
    args = parser.parse_args()
    dest = BUILD / ('protocol-tests' if args.protocol_only else 'host-tests')
    dest.mkdir(parents=True, exist_ok=True)
    sources = [ROOT / 'tests' / (name + '.java') for name in PROTOCOL_TESTS]
    if args.protocol_only:
        package = ROOT / 'android-app/app/src/main/java/io/github/xgl34222220/bichen'
        sources += [package / (name + '.java') for name in ('DnsPacket', 'DnsCache', 'DnsUpstream', 'DnsResponseFilter', 'RuleProfiles', 'NetworkEpoch', 'RuleUpdateGate', 'ModuleArchive')]
        cp = str(dest)
    else:
        _, android = sdk_paths()
        cp = str(BUILD / 'classes') + os.pathsep + str(android)
        sources.append(ROOT / 'tests/rule_parser_test.java')
    javac, java = java_tool('javac'), java_tool('java')
    command = [javac] if javac else [java, '-jar', str(LOCAL_TOOLS / 'ecj.jar')]
    subprocess.run(command + ['-source', '8', '-target', '8', '-encoding', 'UTF-8', '-cp', cp, '-d', str(dest)] + list(map(str, sources)), check=True)
    runtime = str(dest) + os.pathsep + cp
    for name in PROTOCOL_TESTS:
        subprocess.run([java, '-cp', runtime, 'io.github.xgl34222220.bichen.' + name], check=True)
    if not args.protocol_only:
        subprocess.run([java, '-cp', runtime, 'io.github.xgl34222220.bichen.RuleStoreParserTest'] + [str(ROOT / ('module/rules/' + s + '.txt')) for s in ['adaway', 'china', 'tracking', 'hagezi']], check=True)

if __name__ == '__main__':
    main()
