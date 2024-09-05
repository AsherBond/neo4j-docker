package com.neo4j.docker;

import com.neo4j.docker.utils.DatabaseIO;
import com.neo4j.docker.utils.TemporaryFolderManager;
import com.neo4j.docker.utils.TestSettings;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

import static com.neo4j.docker.utils.WaitStrategies.waitForBoltReady;

public class TestDockerComposeSecrets
{
    private static final Logger log = LoggerFactory.getLogger( TestDockerComposeSecrets.class );

    private static final int DEFAULT_BOLT_PORT = 7687;
    private static final int DEFAULT_HTTP_PORT = 7474;
    private static final Path TEST_RESOURCES_PATH = Paths.get( "src", "test", "resources", "dockersecrets" );

    @RegisterExtension
    public static TemporaryFolderManager temporaryFolderManager = new TemporaryFolderManager();

    private DockerComposeContainer createContainer( File composeFile, Path containerRootDir, String serviceName, String password )
    {
        var container = new DockerComposeContainer( composeFile );

        container.withExposedService( serviceName, DEFAULT_BOLT_PORT )
                 .withExposedService( serviceName, DEFAULT_HTTP_PORT )
                 .withEnv( "NEO4J_IMAGE", TestSettings.IMAGE_ID.asCanonicalNameString() )
                 .withEnv( "HOST_ROOT", containerRootDir.toAbsolutePath().toString() )
                 .waitingFor( serviceName, waitForBoltReady( Duration.ofSeconds( 90 ) ) )
                 .withLogConsumer( serviceName, new Slf4jLogConsumer( log ) );

        return container;
    }

    @Test
    void shouldCreateContainerAndConnect() throws Exception
    {
        var tmpDir = temporaryFolderManager.createFolder( "Simple_Container_Compose" );
        var composeFile = copyDockerComposeResourceFile( tmpDir, TEST_RESOURCES_PATH.resolve( "simple-container-compose.yml" ).toFile() );
        var serviceName = "simplecontainer";

        try ( var dockerComposeContainer = createContainer( composeFile, tmpDir, serviceName, "simplecontainerpassword" ) )
        {
            dockerComposeContainer.start();

            var dbio = new DatabaseIO( dockerComposeContainer.getServiceHost( serviceName, DEFAULT_BOLT_PORT ),
                                       dockerComposeContainer.getServicePort( serviceName, DEFAULT_BOLT_PORT ) );
            dbio.verifyConnectivity( "neo4j", "simplecontainerpassword" );
        }
    }

    @Test
    void shouldCreateContainerWithSecretPasswordAndConnect() throws Exception
    {
        var tmpDir = temporaryFolderManager.createFolder( "Container_Compose_With_Secrets" );
        var composeFile = copyDockerComposeResourceFile( tmpDir, TEST_RESOURCES_PATH.resolve( "container-compose-with-secrets.yml" ).toFile() );
        var serviceName = "secretscontainer";

        var newSecretPassword = "neo4j/newSecretPassword";
        Files.createFile( tmpDir.resolve( "neo4j_auth.txt" ) );
        Files.writeString( tmpDir.resolve( "neo4j_auth.txt" ), newSecretPassword );

        try ( var dockerComposeContainer = createContainer( composeFile, tmpDir, serviceName, "newSecretPassword" ) )
        {
            dockerComposeContainer.start();
            var dbio = new DatabaseIO( dockerComposeContainer.getServiceHost( serviceName, DEFAULT_BOLT_PORT ),
                                       dockerComposeContainer.getServicePort( serviceName, DEFAULT_BOLT_PORT ) );
            dbio.verifyConnectivity( "neo4j", "newSecretPassword" );
        }
    }

    @Test
    void shouldOverrideVariableWithSecretValue() throws Exception
    {
        var tmpDir = temporaryFolderManager.createFolder( "Container_Compose_With_Secrets_Override" );
        Files.createDirectories( tmpDir.resolve( "neo4j" ).resolve( "config" ) );

        var composeFile = copyDockerComposeResourceFile( tmpDir, TEST_RESOURCES_PATH.resolve( "container-compose-with-secrets-override.yml" ).toFile() );
        var serviceName = "secretsoverridecontainer";

        var newSecretPageCache = "50M";
        Files.createFile( tmpDir.resolve( "neo4j_pagecache.txt" ) );
        Files.writeString( tmpDir.resolve( "neo4j_pagecache.txt" ), newSecretPageCache );

        try ( var dockerComposeContainer = createContainer( composeFile, tmpDir, serviceName, "none" ) )
        {
            dockerComposeContainer.start();

            var configFile = tmpDir.resolve( "neo4j" ).resolve( "config" ).resolve( "neo4j.conf" ).toFile();
            Assertions.assertTrue( configFile.exists(), "neo4j.conf file does not exist" );
            Assertions.assertTrue( configFile.canRead(), "cannot read neo4j.conf file" );

            Assertions.assertFalse( Files.readAllLines( configFile.toPath() ).contains( "dbms.memory.pagecache.size=10M" ) );
            Assertions.assertTrue( Files.readAllLines( configFile.toPath() ).contains( "dbms.memory.pagecache.size=50M" ) );
        }
    }

    @Test
    void shouldFailIfSecretFileDoesNotExist() throws Exception
    {
        var tmpDir = temporaryFolderManager.createFolder( "Container_Compose_With_Secrets_Override" );
        var composeFile = copyDockerComposeResourceFile( tmpDir, TEST_RESOURCES_PATH.resolve( "container-compose-with-secrets-override.yml" ).toFile() );
        var serviceName = "secretsoverridecontainer";

        try ( var dockerComposeContainer = createContainer( composeFile, tmpDir, serviceName, "none" ) )
        {
            dockerComposeContainer.start();
        }
        catch ( Exception e )
        {
            Assertions.assertTrue( e.getMessage().contains( "Container startup failed for image" ) );
        }
    }

    private File copyDockerComposeResourceFile( Path targetDirectory, File resourceFile ) throws IOException
    {
        File compose_file = new File( targetDirectory.toString(), resourceFile.getName() );
        if ( compose_file.exists() )
        {
            Files.delete( compose_file.toPath() );
        }
        Files.copy( resourceFile.toPath(), Paths.get( compose_file.getPath() ) );
        return compose_file;
    }
}
