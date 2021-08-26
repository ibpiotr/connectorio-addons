package org.connectorio.addons.managed.thing.internal.reader;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.connectorio.addons.managed.thing.model.Things;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XStreamThingReaderTest {

  @TempDir
  static Path directory;

  @Test
  void testReader() throws Exception {
    XStreamThingReader reader = new XStreamThingReader();
    Things things = reader.readFromXML(getClass().getResource("/things.xml"));

    String value = reader.write(things);

    Path testFile = directory.resolve("test.xml");
    Files.write(testFile, value.getBytes());

    Things deserialized = reader.readFromXML(testFile.toUri().toURL());
    assertThat(deserialized).isEqualTo(things);
  }

}