package org.ligoj.app.plugin.scm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.transaction.Transactional;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ligoj.app.AbstractAppTest;
import org.ligoj.app.model.Node;
import org.ligoj.app.model.Parameter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Test class of {@link ScmResource}
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = "classpath:/META-INF/spring/application-context-test.xml")
@Rollback
@Transactional
public class ScmResourceTest extends AbstractAppTest {

	@Autowired
	private ScmResource resource;

	@BeforeEach
	public void prepareData() throws IOException {
		persistEntities("csv", new Class[] { Node.class, Parameter.class }, StandardCharsets.UTF_8.name());
	}

	@Test
	public void getKey() {
		// Coverage only
		Assertions.assertEquals("service:scm", resource.getKey());
	}
}
