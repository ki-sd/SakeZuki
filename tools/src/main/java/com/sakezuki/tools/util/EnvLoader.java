package com.sakezuki.tools.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class EnvLoader {

    private static final Map<String, String> ENV = new HashMap<>();

    static {
        load();
    }

    private EnvLoader() {
    }

    private static void load() {
        Path envPath=findEnvFile();

        if (envPath==null) {
            throw new IllegalStateException(".env 파일을 찾을 수 없습니다.");
        }

        try {
            List<String> lines=Files.readAllLines(envPath);

            for (String line : lines) {
                line=line.trim();

                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                int index=line.indexOf('=');

                if (index<=0) {
                    continue;
                }

                String key=line.substring(0, index).trim();
                String value=line.substring(index + 1).trim();

                // "value", 'value' 형태도 허용
                if (value.length()>=2) {
                    if ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'"))) {
                        value=value.substring(1, value.length()-1);
                    }
                }

                ENV.put(key, value);
            }

            System.out.println(".env 로드 완료: "+envPath.toAbsolutePath());

        } catch (IOException e) {
            throw new IllegalStateException(".env 파일 읽기 실패", e);
        }
    }

    private static Path findEnvFile() {
        Path current=Path.of("").toAbsolutePath();

        // 실행 위치부터 상위 디렉터리까지 탐색
        while (current!=null) {
            Path candidate=current.resolve(".env");

            if (Files.exists(candidate)) {
                return candidate;
            }

            current=current.getParent();
        }

        return null;
    }

    public static String get(String key) {
        // OS/IntelliJ 환경변수가 있으면 그것을 우선 사용
        String systemValue=System.getenv(key);

        if (systemValue!=null && !systemValue.isBlank()) {
            return systemValue;
        }

        String value=ENV.get(key);

        if (value==null || value.isBlank()) {
            throw new IllegalStateException("환경변수가 설정되지 않았습니다: "+key);
        }

        return value;
    }
}