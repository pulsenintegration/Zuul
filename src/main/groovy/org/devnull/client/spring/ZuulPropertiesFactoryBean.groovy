package org.devnull.client.spring

import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.BasicResponseHandler
import org.apache.http.impl.client.DefaultHttpClient
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.devnull.client.spring.cache.PropertiesObjectStore
import org.jasypt.encryption.StringEncryptor
import org.jasypt.encryption.pbe.StandardPBEStringEncryptor
import org.jasypt.encryption.pbe.config.EnvironmentPBEConfig
import org.jasypt.properties.EncryptableProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.FactoryBean
import org.springframework.beans.factory.InitializingBean

import java.security.Security

class ZuulPropertiesFactoryBean implements InitializingBean, DisposableBean, FactoryBean<Properties> {

    final def log = LoggerFactory.getLogger(this.class)

    static {
        Security.addProvider(new BouncyCastleProvider())
    }

    /**
     * Name of the system environment variable which can be set to resolve the password to
     * decrypt secured values.
     *
     * Value: ZUUL_PASSWORD
     */
    static final String DEFAULT_PASSWORD_VARIABLE = "ZUUL_PASSWORD"

    /**
     * Available algorithms for use with their associated meta-data.
     */
    static final Map ALGORITHM_CONFIG = [
            'PBEWITHSHA256AND128BITAES-CBC-BC': [
                    provider: BouncyCastleProvider.PROVIDER_NAME,
                    hashIterations: 1000,
                    secure:true
            ],
            'PBEWithSHAAnd2-KeyTripleDES-CBC': [
                    provider: BouncyCastleProvider.PROVIDER_NAME,
                    hashIterations: 1000,
                    secure:true
            ],
            'PBEWithMD5AndTripleDES' : [
                    provider: null,
                    hashIterations: 1000,
                    secure:true
            ],
            'PBEWithMD5AndDES' : [
                    provider: null,
                    hashIterations: 1000,
                    secure:false
            ]
    ]

    static final List<String> OPTIONAL_ATTRIBUTES = ["host", "port", "context", "environment", "password", "ssl", "algorithm"]

    /**
     * Used to invoke the web service. If not supplied, a default client will be provided.
     */
    HttpClient httpClient

    /**
     * Host or IP address of the zuul server
     *
     * default = localhost
     */
    String host = "localhost"

    /**
     * TCP port where the zuul application can be reached.
     *
     * default = 80
     */
    Integer port = 80

    /**
     * Context path where the zuul application resides
     *
     * default = "/zuul"
     */
    String context = "/zuul"

    /**
     * Environment for the configuration
     *
     * default = "dev"
     */
    String environment = "dev"

    /**
     * Optional password. If not set, it will look for system property or environment variable.
     *
     * @see org.devnull.client.spring.ZuulPropertiesFactoryBean#DEFAULT_PASSWORD_VARIABLE
     */
    String password = null

    /**
     * Optional algorithm. See the namespace xsd for complete list of supported values.
     *
     * default: PBEWithMD5AndDES to maintain backwards compatiblity. You really should use a stronger algorithm though.
     */
    String algorithm = "PBEWithMD5AndDES"

    /**
     * Name of the configuration to fetch
     */
    String config

    /**
     * Use HTTPS or HTTP?
     *
     * default = false
     */
    Boolean ssl = false

    /**
     * Used to cache requests.
     *
     * @see org.devnull.client.spring.cache.PropertiesObjectFileSystemStore
     */
    PropertiesObjectStore propertiesStore

    ZuulPropertiesFactoryBean(String config) {
        this.config = config
    }

    Properties fetchProperties() {
        def handler = new BasicResponseHandler();
        def get = new HttpGet(uri)
        log.info("Fetching properties from {}", get)
        try {
            def responseBody = httpClient.execute(get, handler);
            def properties = new Properties()
            properties.load(new StringReader(responseBody))
            log.debug("Loading properties: {}", properties)
            if (propertiesStore) {
                propertiesStore.put(environment, config, properties)
            }
            return properties
        } catch (Exception e) {
            log.error("Unable to fetch remote properties file from: {}, error:{}. Attempting to find cached copy..", get, e.message)
            if (!propertiesStore) {
                log.error("Cache not configured. Giving up...")
                log.error("Hint: use zuul:file-store to configure locally cached copies as a fail-safe.")
                throw e
            }
            return propertiesStore.get(environment, config)
        }
    }


    Properties decrypt(Properties properties) {
        def encryptor = new StandardPBEStringEncryptor(config: createPbeConfig())
        return new EncryptableProperties(properties, encryptor)
    }

    URI getUri() {
        return new URI("${ssl ? "https" : "http"}://${host}:${port}${context}/settings/${environment}/${config}.properties")
    }

    void afterPropertiesSet() {
        if (!httpClient) {
            httpClient = new DefaultHttpClient();
        }
    }

    void destroy() {
        httpClient.connectionManager.shutdown()
    }

    Properties getObject() {
        return decrypt(fetchProperties())
    }

    Class<?> getObjectType() {
        return Properties
    }

    boolean isSingleton() {
        return false
    }

    protected EnvironmentPBEConfig createPbeConfig() {
        def pbeConfig = new EnvironmentPBEConfig()
        if (password) {
            pbeConfig.password = password
        } else {
            pbeConfig.passwordSysPropertyName = DEFAULT_PASSWORD_VARIABLE
        }
        def algorithmConfig = ALGORITHM_CONFIG[algorithm]
        pbeConfig.algorithm = algorithm
        pbeConfig.keyObtentionIterations = algorithmConfig.hashIterations
        pbeConfig.providerName = algorithmConfig.provider
        if (!algorithmConfig.secure) {
            log.warn("{} is not considered a secure algorithm. Please consider using an alternative.", algorithm)
        }
        return pbeConfig
    }
}