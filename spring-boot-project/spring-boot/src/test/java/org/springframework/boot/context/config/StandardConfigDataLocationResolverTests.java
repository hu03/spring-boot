/*
 * Copyright 2012-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.context.config;

import java.io.File;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link StandardConfigDataLocationResolver}.
 *
 * @author Madhura Bhave
 * @author Phillip Webb
 * @author Moritz Halbritter
 * @author Sijun Yang
 */
class StandardConfigDataLocationResolverTests {

	private StandardConfigDataLocationResolver resolver;

	private final ConfigDataLocationResolverContext context = mock(ConfigDataLocationResolverContext.class);

	private MockEnvironment environment;

	private Binder environmentBinder;

	private final ResourceLoader resourceLoader = new DefaultResourceLoader();

	@BeforeEach
	void setup() {
		this.environment = new MockEnvironment();
		this.environmentBinder = Binder.get(this.environment);
		this.resolver = new StandardConfigDataLocationResolver(new DeferredLogs(), this.environmentBinder,
				this.resourceLoader);
	}

	@Test
	void isResolvableAlwaysReturnsTrue() {
		assertThat(this.resolver.isResolvable(this.context, ConfigDataLocation.of("test"))).isTrue();
	}

	@Test
	void resolveWhenLocationIsDirectoryResolvesAllMatchingFilesInDirectory() {
		ConfigDataLocation location = ConfigDataLocation.of("classpath:/configdata/properties/");
		List<StandardConfigDataResource> locations = this.resolver.resolve(this.context, location);
		assertThat(locations).hasSize(1);
		assertThat(locations).extracting(Object::toString)
			.containsExactly("class path resource [configdata/properties/application.properties]");
	}

	@Test
	void resolveWhenLocationIsFileResolvesFile() {
		ConfigDataLocation location = ConfigDataLocation
			.of("file:src/test/resources/configdata/properties/application.properties");
		List<StandardConfigDataResource> locations = this.resolver.resolve(this.context, location);
		assertThat(locations).hasSize(1);
		assertThat(locations).extracting(Object::toString)
			.containsExactly(
					filePath("src", "test", "resources", "configdata", "properties", "application.properties"));
	}

	@Test
	void resolveWhenLocationIsFileAndNoMatchingLoaderThrowsException() {
		ConfigDataLocation location = ConfigDataLocation
			.of("file:src/test/resources/configdata/properties/application.unknown");
		assertThatIllegalStateException().isThrownBy(() -> this.resolver.resolve(this.context, location))
			.withMessageStartingWith("Unable to load config data from")
			.satisfies((ex) -> assertThat(ex.getCause()).hasMessageStartingWith("File extension is not known"));
	}

	@Test
	void resolveWhenLocationHasUnknownPrefixAndNoMatchingLoaderThrowsException() {
		ConfigDataLocation location = ConfigDataLocation
			.of("typo:src/test/resources/configdata/properties/application.unknown");
		assertThatIllegalStateException().isThrownBy(() -> this.resolver.resolve(this.context, location))
			.withMessageStartingWith("Unable to load config data from")
			.satisfies((ex) -> assertThat(ex.getCause()).hasMessageStartingWith(
					"Incorrect ConfigDataLocationResolver chosen or file extension is not known to any PropertySourceLoader"));
	}

	@Test
	void resolveWhenLocationWildcardIsSpecifiedForClasspathLocationThrowsException() {
		ConfigDataLocation location = ConfigDataLocation.of("classpath*:application.properties");
		assertThatIllegalStateException().isThrownBy(() -> this.resolver.resolve(this.context, location))
			.withMessageContaining("Location 'classpath*:application.properties' cannot use classpath wildcards");
	}

	@Test
	void resolveWhenLocationWildcardIsNotBeforeLastSlashThrowsException() {
		ConfigDataLocation location = ConfigDataLocation.of("file:src/test/resources/*/config/");
		assertThatIllegalStateException().isThrownBy(() -> this.resolver.resolve(this.context, location))
			.withMessageStartingWith("Location '")
			.withMessageEndingWith("' must end with '*/'");
	}

	@Test
	void createWhenConfigNameHasWildcardThrowsException() {
		this.environment.setProperty("spring.config.name", "*/application");
		assertThatIllegalStateException()
			.isThrownBy(() -> new StandardConfigDataLocationResolver(new DeferredLogs(), this.environmentBinder,
					this.resourceLoader))
			.withMessageStartingWith("Config name '")
			.withMessageEndingWith("' cannot contain '*'");
	}

	@Test
	void resolveWhenLocationHasMultipleWildcardsThrowsException() {
		ConfigDataLocation location = ConfigDataLocation.of("file:src/test/resources/config/**/");
		assertThatIllegalStateException().isThrownBy(() -> this.resolver.resolve(this.context, location))
			.withMessageStartingWith("Location '")
			.withMessageEndingWith("' cannot contain multiple wildcards");
	}

	@Test
	void resolveWhenLocationIsWildcardDirectoriesRestrictsToOneLevelDeep() {
		ConfigDataLocation location = ConfigDataLocation.of("file:src/test/resources/config/*/");
		this.environment.setProperty("spring.config.name", "testproperties");
		this.resolver = new StandardConfigDataLocationResolver(new DeferredLogs(), this.environmentBinder,
				this.resourceLoader);
		List<StandardConfigDataResource> locations = this.resolver.resolve(this.context, location);
		assertThat(locations).hasSize(3);
		assertThat(locations).extracting(Object::toString)
			.contains(filePath("src", "test", "resources", "config", "1-first", "testproperties.properties"))
			.contains(filePath("src", "test", "resources", "config", "2-second", "testproperties.properties"))
			.doesNotContain(filePath("src", "test", "resources", "config", "3-third", "testproperties.properties"));
	}

	@Test
	void resolveWhenLocationIsWildcardDirectoriesSortsAlphabeticallyBasedOnAbsolutePath() {
		ConfigDataLocation location = ConfigDataLocation.of("file:src/test/resources/config/*/");
		this.environment.setProperty("spring.config.name", "testproperties");
		this.resolver = new StandardConfigDataLocationResolver(new DeferredLogs(), this.environmentBinder,
				this.resourceLoader);
		List<StandardConfigDataResource> locations = this.resolver.resolve(this.context, location);
		assertThat(locations).extracting(Object::toString)
			.containsExactly(filePath("src", "test", "resources", "config", "0-empty", "testproperties.properties"),
					filePath("src", "test", "resources", "config", "1-first", "testproperties.properties"),
					filePath("src", "test", "resources", "config", "2-second", "testproperties.properties"));
	}

	@Test
	void resolveWhenLocationIsWildcardAndMatchingFilePresentShouldNotFail() {
		ConfigDataLocation location = ConfigDataLocation.of("optional:file:src/test/resources/a-file/*/");
		assertThatNoException().isThrownBy(() -> this.resolver.resolve(this.context, location));
	}

	@Test
	void resolveWhenLocationIsWildcardFilesLoadsAllFilesThatMatch() {
		ConfigDataLocation location = ConfigDataLocation
			.of("file:src/test/resources/config/*/testproperties.properties");
		List<StandardConfigDataResource> locations = this.resolver.resolve(this.context, location);
		assertThat(locations).hasSize(3);
		assertThat(locations).extracting(Object::toString)
			.contains(filePath("src", "test", "resources", "config", "1-first", "testproperties.properties"))
			.contains(filePath("src", "test", "resources", "config", "2-second", "testproperties.properties"))
			.doesNotContain(
					filePath("src", "test", "resources", "config", "nested", "3-third", "testproperties.properties"));
	}

	@Test
	void resolveWhenLocationIsRelativeAndFileResolves() {
		this.environment.setProperty("spring.config.name", "other");
		ConfigDataLocation location = ConfigDataLocation.of("other.properties");
		this.resolver = new StandardConfigDataLocationResolver(new DeferredLogs(), this.environmentBinder,
				this.resourceLoader);
		StandardConfigDataReference parentReference = new StandardConfigDataReference(
				ConfigDataLocation.of("classpath:configdata/properties/application.properties"), null,
				"classpath:configdata/properties/application", null, "properties",
				new PropertiesPropertySourceLoader());
		ClassPathResource parentResource = new ClassPathResource("configdata/properties/application.properties");
		StandardConfigDataResource parent = new StandardConfigDataResource(parentReference, parentResource);
		given(this.context.getParent()).willReturn(parent);
		List<StandardConfigDataResource> locations = this.resolver.resolve(this.context, location);
		assertThat(locations).hasSize(1);
		assertThat(locations).extracting(Object::toString)
			.contains("class path resource [configdata/properties/other.properties]");
	}

	@Test
	void resolveWhenLocationIsRelativeAndDirectoryResolves() {
		this.environment.setProperty("spring.config.name", "testproperties");
		ConfigDataLocation location = ConfigDataLocation.of("nested/3-third/");
		this.resolver = new StandardConfigDataLocationResolver(new DeferredLogs(), this.environmentBinder,
				this.resourceLoader);
		StandardConfigDataReference parentReference = new StandardConfigDataReference(
				ConfigDataLocation.of("optional:classpath:configdata/"), null, "classpath:config/specific", null,
				"properties", new PropertiesPropertySourceLoader());
		ClassPathResource parentResource = new ClassPathResource("config/specific.properties");
		StandardConfigDataResource parent = new StandardConfigDataResource(parentReference, parentResource);
		given(this.context.getParent()).willReturn(parent);
		List<StandardConfigDataResource> locations = this.resolver.resolve(this.context, location);
		assertThat(locations).hasSize(1);
		assertThat(locations).extracting(Object::toString)
			.contains("class path resource [config/nested/3-third/testproperties.properties]");
	}

	@Test
	void resolveWhenLocationIsRelativeAndNoMatchingLoaderThrowsException() {
		ConfigDataLocation location = ConfigDataLocation.of("application.other");
		StandardConfigDataReference parentReference = new StandardConfigDataReference(
				ConfigDataLocation.of("classpath:configdata/properties/application.properties"), null,
				"configdata/properties/application", null, "properties", new PropertiesPropertySourceLoader());
		ClassPathResource parentResource = new ClassPathResource("configdata/properties/application.properties");
		StandardConfigDataResource parent = new StandardConfigDataResource(parentReference, parentResource);
		given(this.context.getParent()).willReturn(parent);
		assertThatIllegalStateException().isThrownBy(() -> this.resolver.resolve(this.context, location))
			.withMessageStartingWith("Unable to load config data from 'application.other'")
			.satisfies((ex) -> assertThat(ex.getCause()).hasMessageStartingWith("File extension is not known"));
	}

	@Test
	void resolveWhenLocationUsesOptionalExtensionSyntaxResolves() throws Exception {
		ConfigDataLocation location = ConfigDataLocation.of("classpath:/application-props-no-extension[.properties]");
		List<StandardConfigDataResource> locations = this.resolver.resolve(this.context, location);
		assertThat(locations).hasSize(1);
		StandardConfigDataResource resolved = locations.get(0);
		assertThat(resolved.getResource().getFilename()).endsWith("application-props-no-extension");
		ConfigData loaded = new StandardConfigDataLoader().load(null, resolved);
		PropertySource<?> propertySource = loaded.getPropertySources().get(0);
		assertThat(propertySource.getProperty("withnotext")).isEqualTo("test");
	}

	@Test
	void resolveProfileSpecificReturnsProfileSpecificFiles() {
		ConfigDataLocation location = ConfigDataLocation.of("classpath:/configdata/properties/");
		this.environment.setActiveProfiles("dev");
		Profiles profiles = new Profiles(this.environment, this.environmentBinder, Collections.emptyList());
		List<StandardConfigDataResource> locations = this.resolver.resolveProfileSpecific(this.context, location,
				profiles);
		assertThat(locations).hasSize(1);
		assertThat(locations).extracting(Object::toString)
			.containsExactly("class path resource [configdata/properties/application-dev.properties]");
	}

	@Test
	void resolveProfileSpecificWhenLocationIsFileReturnsEmptyList() {
		ConfigDataLocation location = ConfigDataLocation.of("classpath:/configdata/properties/application.properties");
		Profiles profiles = mock(Profiles.class);
		given(profiles.iterator()).willReturn(Collections.emptyIterator());
		given(profiles.getActive()).willReturn(Collections.singletonList("dev"));
		List<StandardConfigDataResource> locations = this.resolver.resolveProfileSpecific(this.context, location,
				profiles);
		assertThat(locations).isEmpty();
	}

	@Test
	void resolveWhenOptionalAndLoaderIsUnknownShouldNotFail() {
		ConfigDataLocation location = ConfigDataLocation.of("optional:some-unknown-loader:dummy.properties");
		assertThatNoException().isThrownBy(() -> this.resolver.resolve(this.context, location));
	}

	@Test
	void resolveWhenOptionalAndLoaderIsUnknownAndExtensionIsUnknownShouldNotFail() {
		ConfigDataLocation location = ConfigDataLocation
			.of("optional:some-unknown-loader:dummy.some-unknown-extension");
		assertThatNoException().isThrownBy(() -> this.resolver.resolve(this.context, location));
	}

	@Test
	void resolveWhenOptionalAndExtensionIsUnknownShouldNotFail() {
		ConfigDataLocation location = ConfigDataLocation.of("optional:file:dummy.some-unknown-extension");
		assertThatNoException().isThrownBy(() -> this.resolver.resolve(this.context, location));
	}

	@Test
	void resolveProfileSpecificWhenProfileIsValidShouldNotThrowException() {
		ConfigDataLocation location = ConfigDataLocation.of("classpath:/configdata/properties/");
		this.environment.setActiveProfiles("dev-test_123");
		Profiles profiles = new Profiles(this.environment, this.environmentBinder, Collections.emptyList());
		assertThatNoException()
			.isThrownBy(() -> this.resolver.resolveProfileSpecific(this.context, location, profiles));
	}

	@Test
	void resolveProfileSpecificWithNonAsciiCharactersShouldNotThrowException() {
		ConfigDataLocation location = ConfigDataLocation.of("classpath:/configdata/properties/");
		this.environment.setActiveProfiles("dev-테스트_123");
		Profiles profiles = new Profiles(this.environment, this.environmentBinder, Collections.emptyList());
		assertThatNoException()
			.isThrownBy(() -> this.resolver.resolveProfileSpecific(this.context, location, profiles));
	}

	@Test
	void resolveProfileSpecificWithAdditionalValidProfilesShouldNotThrowException() {
		ConfigDataLocation location = ConfigDataLocation.of("classpath:/configdata/properties/");
		this.environment.setActiveProfiles("dev-test");
		Profiles profiles = new Profiles(this.environment, this.environmentBinder, List.of("prod-test", "stage-test"));
		assertThatNoException()
			.isThrownBy(() -> this.resolver.resolveProfileSpecific(this.context, location, profiles));
	}

	@Test
	void resolveProfileSpecificWhenProfileStartsWithSymbolThrowsException() {
		ConfigDataLocation location = ConfigDataLocation.of("classpath:/configdata/properties/");
		this.environment.setActiveProfiles("-dev");
		Profiles profiles = new Profiles(this.environment, this.environmentBinder, Collections.emptyList());
		assertThatIllegalStateException()
			.isThrownBy(() -> this.resolver.resolveProfileSpecific(this.context, location, profiles))
			.withMessageStartingWith("Invalid profile '-dev': must not start with '-' or '_'");
	}

	@Test
	void resolveProfileSpecificWhenProfileStartsWithUnderscoreThrowsException() {
		ConfigDataLocation location = ConfigDataLocation.of("classpath:/configdata/properties/");
		this.environment.setActiveProfiles("_dev");
		Profiles profiles = new Profiles(this.environment, this.environmentBinder, Collections.emptyList());
		assertThatIllegalStateException()
			.isThrownBy(() -> this.resolver.resolveProfileSpecific(this.context, location, profiles))
			.withMessageStartingWith("Invalid profile '_dev': must not start with '-' or '_'");
	}

	@Test
	void resolveProfileSpecificWhenProfileEndsWithSymbolThrowsException() {
		ConfigDataLocation location = ConfigDataLocation.of("classpath:/configdata/properties/");
		this.environment.setActiveProfiles("dev-");
		Profiles profiles = new Profiles(this.environment, this.environmentBinder, Collections.emptyList());
		assertThatIllegalStateException()
			.isThrownBy(() -> this.resolver.resolveProfileSpecific(this.context, location, profiles))
			.withMessageStartingWith("Invalid profile 'dev-': must not end with '-' or '_'");
	}

	@Test
	void resolveProfileSpecificWhenProfileEndsWithUnderscoreThrowsException() {
		ConfigDataLocation location = ConfigDataLocation.of("classpath:/configdata/properties/");
		this.environment.setActiveProfiles("dev_");
		Profiles profiles = new Profiles(this.environment, this.environmentBinder, Collections.emptyList());
		assertThatIllegalStateException()
			.isThrownBy(() -> this.resolver.resolveProfileSpecific(this.context, location, profiles))
			.withMessageStartingWith("Invalid profile 'dev_': must not end with '-' or '_'");
	}

	@Test
	void resolveProfileSpecificWhenProfileContainsInvalidCharactersThrowsException() {
		ConfigDataLocation location = ConfigDataLocation.of("classpath:/configdata/properties/");
		this.environment.setActiveProfiles("dev*test");
		Profiles profiles = new Profiles(this.environment, this.environmentBinder, Collections.emptyList());
		assertThatIllegalStateException()
			.isThrownBy(() -> this.resolver.resolveProfileSpecific(this.context, location, profiles))
			.withMessageStartingWith("Invalid profile 'dev*test': must contain only letters or digits or '-' or '_'");
	}

	private String filePath(String... components) {
		return "file [" + String.join(File.separator, components) + "]";
	}

}
