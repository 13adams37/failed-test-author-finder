# failed-test-author-finder

Простой standalone CLI на Java 17, который:
- читает готовые JUnit XML отчёты;
- находит упавшие тесты;
- ищет исходный Java-файл теста по FQCN;
- пытается сопоставить упавший test case с конкретным методом или `@DisplayName`;
- делает `git blame` по диапазону строк;
- печатает текстовый отчёт с кандидатами-авторами.

## Что умеет
- JUnit XML из Gradle (`build/test-results/...`)
- JUnit 5 display names (`@DisplayName`) best effort
- fallback на диапазон класса, если метод не найден
- top-N авторов по доле строк из `git blame`
- вывод в stdout или в текстовый файл

## Ограничения
- Это **best effort**, а не абсолютная истина.
- Dynamic tests и сложные шаблоны именования parameterized tests могут не сматчиться на метод и уйдут в fallback на класс.
- Для корректного результата репозиторий должен быть git checkout'ом, а тестовые файлы — доступны локально.
- Если история сильно засорена массовыми форматирующими коммитами, положите `.git-blame-ignore-revs` в корень repo — инструмент подхватит его автоматически.

## Сборка
```bash
./gradlew clean jar
```

## Запуск
Из любого checkout, где уже есть JUnit XML отчёты:
```bash
java -jar build/libs/failed-test-author-finder-1.0.0.jar \
  --repo /path/to/repo \
  --reports "**/build/test-results/**/*.xml" \
  --top 3
```

С записью в файл:
```bash
java -jar build/libs/failed-test-author-finder-1.0.0.jar \
  --repo /path/to/repo \
  --out failed-test-authors.txt
```

## Параметры
- `--repo <path>` — путь до git-репозитория. По умолчанию `.`
- `--reports <glob1,glob2,...>` — glob-паттерны для XML-отчётов. По умолчанию `**/build/test-results/**/*.xml`
- `--top <N>` — сколько авторов показывать на тест. По умолчанию `3`
- `--out <file>` — файл для текстового отчёта. Если не задан, печатает в stdout
- `--help` — краткая справка

## Формат отчёта
Для каждого упавшего test case инструмент печатает примерно такое:
```text
FAILED TEST : com.acme.auth.TokenServiceTest#shouldRejectExpiredToken()
REPORT FILE : service-a/build/test-results/test/TEST-com.acme.auth.TokenServiceTest.xml
SOURCE FILE : service-a/src/test/java/com/acme/auth/TokenServiceTest.java
MATCH       : METHOD (HIGH)
LINES       : 112-146
AUTHORS     :
  1. Ivan Petrov <ivan@company.com> - 57.14% (20/35 lines)
  2. Anna Sidorova <anna@company.com> - 31.43% (11/35 lines)
  3. Pavel Smirnov <pavel@company.com> - 11.43% (4/35 lines)
```

## Как это работает
1. Сканирует XML-отчёты и вытаскивает test case с `<failure>` или `<error>`.
2. Ищет Java-файл теста по FQCN через индекс tracked `.java` файлов.
3. Парсит исходник через JDK Compiler API (`com.sun.source.*`), а не regexp.
4. Пытается найти диапазон строк:
   - exact method name;
   - exact `@DisplayName`;
   - normalized match;
   - fallback на диапазон класса.
5. Запускает `git blame --line-porcelain -M -C -C -L start,end -- <file>`.
6. Считает долю строк по авторам и печатает top-N.
