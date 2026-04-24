/*
 * SonarSource Go
 * Copyright (C) SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * You can redistribute and/or modify this program under the terms of
 * the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
package org.sonar.go.plugin;

import java.net.URI;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.sonar.plugins.go.api.checks.GoModFileData;
import org.sonar.plugins.go.api.checks.GoVersion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GoModFileDataStoreTest {

  private final GoModFileData goModeFileData1 = mock(GoModFileData.class);
  private final GoModFileData goModeFileData2 = mock(GoModFileData.class);

  @Test
  void shouldValidateGoModRootFolder() {
    GoModFileDataStore store = new GoModFileDataStore();
    store.addGoModFile(URI.create("/go.mod"), goModeFileData1);
    store.complete();

    assertThat(store.getRootPath()).isEqualTo("/");
    assertThat(store.retrieveClosestGoModFileData("/")).isEqualTo(goModeFileData1);
    assertThat(store.retrieveClosestGoModFileData("/file.go")).isEqualTo(goModeFileData1);
    assertThat(store.retrieveClosestGoModFileData("/sub/folders/file.go")).isEqualTo(goModeFileData1);

    assertThat(store.retrieveClosestGoModFileData("")).isEqualTo(GoModFileData.UNKNOWN_DATA);
    assertThat(store.retrieveClosestGoModFileData(".")).isEqualTo(GoModFileData.UNKNOWN_DATA);
  }

  @Test
  void shouldReturnSimpleDirectPath() {
    GoModFileDataStore store = new GoModFileDataStore();
    store.addGoModFile(URI.create("/path/go.mod"), goModeFileData1);
    store.complete();

    assertThat(store.getRootPath()).isEqualTo("/path");
    assertThat(store.retrieveClosestGoModFileData("/path")).isEqualTo(goModeFileData1);
    assertThat(store.retrieveClosestGoModFileData("/path/file.go")).isEqualTo(goModeFileData1);
    assertThat(store.retrieveClosestGoModFileData("/path/sub/folders/file.go")).isEqualTo(goModeFileData1);

    assertThat(store.retrieveClosestGoModFileData("/")).isEqualTo(GoModFileData.UNKNOWN_DATA);
    assertThat(store.retrieveClosestGoModFileData("/other")).isEqualTo(GoModFileData.UNKNOWN_DATA);
    assertThat(store.retrieveClosestGoModFileData("")).isEqualTo(GoModFileData.UNKNOWN_DATA);
    assertThat(store.retrieveClosestGoModFileData(".")).isEqualTo(GoModFileData.UNKNOWN_DATA);
  }

  @Test
  void shouldReturnClosestModuleFile() {
    GoModFileDataStore store = new GoModFileDataStore();
    store.addGoModFile(URI.create("/path/go.mod"), goModeFileData1);
    store.addGoModFile(URI.create("/path/to/subfolder/go.mod"), goModeFileData2);
    store.complete();

    assertThat(store.retrieveClosestGoModFileData("/path")).isEqualTo(goModeFileData1);
    assertThat(store.retrieveClosestGoModFileData("/path/file.go")).isEqualTo(goModeFileData1);
    assertThat(store.retrieveClosestGoModFileData("/path/sub/folders/file.go")).isEqualTo(goModeFileData1);
    assertThat(store.retrieveClosestGoModFileData("/path/to")).isEqualTo(goModeFileData1);
    assertThat(store.retrieveClosestGoModFileData("/path/to/other/subfolder/file.go")).isEqualTo(goModeFileData1);

    assertThat(store.retrieveClosestGoModFileData("/path/to/subfolder")).isEqualTo(goModeFileData2);
    assertThat(store.retrieveClosestGoModFileData("/path/to/subfolder/file.go")).isEqualTo(goModeFileData2);
    assertThat(store.retrieveClosestGoModFileData("/path/to/subfolder/with/more/subfolders/file.go")).isEqualTo(goModeFileData2);

    assertThat(store.retrieveClosestGoModFileData("/")).isEqualTo(GoModFileData.UNKNOWN_DATA);
    assertThat(store.retrieveClosestGoModFileData("/other")).isEqualTo(GoModFileData.UNKNOWN_DATA);
    assertThat(store.retrieveClosestGoModFileData("")).isEqualTo(GoModFileData.UNKNOWN_DATA);
    assertThat(store.retrieveClosestGoModFileData(".")).isEqualTo(GoModFileData.UNKNOWN_DATA);
  }

  @Test
  void shouldReturnEmptyModuleWhenEmptyStore() {
    GoModFileDataStore store = new GoModFileDataStore();
    store.complete();

    assertThat(store.retrieveClosestGoModFileData("")).isEqualTo(GoModFileData.UNKNOWN_DATA);
    assertThat(store.retrieveClosestGoModFileData("/")).isEqualTo(GoModFileData.UNKNOWN_DATA);
    assertThat(store.retrieveClosestGoModFileData(".")).isEqualTo(GoModFileData.UNKNOWN_DATA);
    assertThat(store.retrieveClosestGoModFileData("/path")).isEqualTo(GoModFileData.UNKNOWN_DATA);
    assertThat(store.retrieveClosestGoModFileData("/path/file.go")).isEqualTo(GoModFileData.UNKNOWN_DATA);
  }

  @Test
  void shouldUseExternalGoVersionWhenStoredVersionIsUnknown() {
    var externalVersion = GoVersion.parse("1.21");
    var dataWithUnknownVersion = new GoModFileData("mymodule", GoVersion.UNKNOWN_VERSION, Collections.emptyList(), "/go.mod");
    GoModFileDataStore store = new GoModFileDataStore();
    store.addGoModFile(URI.create("/go.mod"), dataWithUnknownVersion);
    store.complete();
    store.setExternalGoVersion(externalVersion);

    var result = store.retrieveClosestGoModFileData("/file.go");
    assertThat(result.goVersion()).isEqualTo(externalVersion);
    assertThat(result.moduleName()).isEqualTo("mymodule");
  }

  @Test
  void shouldOverrideKnownVersionWithExternalGoVersion() {
    var knownVersion = GoVersion.parse("1.20");
    var externalVersion = GoVersion.parse("1.21");
    var dataWithKnownVersion = new GoModFileData("mymodule", knownVersion, Collections.emptyList(), "/go.mod");
    GoModFileDataStore store = new GoModFileDataStore();
    store.addGoModFile(URI.create("/go.mod"), dataWithKnownVersion);
    store.complete();
    store.setExternalGoVersion(externalVersion);

    var result = store.retrieveClosestGoModFileData("/file.go");
    assertThat(result.goVersion()).isEqualTo(externalVersion);
  }

  @Test
  void shouldReturnExternalGoVersionInCollectGoVersions() {
    var externalVersion = GoVersion.parse("1.21");
    GoModFileDataStore store = new GoModFileDataStore();
    store.complete();
    store.setExternalGoVersion(externalVersion);

    assertThat(store.collectGoVersions()).containsExactly(externalVersion);
  }

  @Test
  void shouldReturnOnlyExternalGoVersionInCollectGoVersionsWhenGoModFilesPresent() {
    var knownVersion = GoVersion.parse("1.20");
    var externalVersion = GoVersion.parse("1.21");
    var dataWithKnownVersion = new GoModFileData("mymodule", knownVersion, Collections.emptyList(), "/go.mod");
    GoModFileDataStore store = new GoModFileDataStore();
    store.addGoModFile(URI.create("/go.mod"), dataWithKnownVersion);
    store.complete();
    store.setExternalGoVersion(externalVersion);

    assertThat(store.collectGoVersions()).containsExactly(externalVersion);
  }
}
