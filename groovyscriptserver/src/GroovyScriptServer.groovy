

import groovy.text.SimpleTemplateEngine
import groovy.text.Template
import groovy.text.TemplateEngine
import groovy.transform.*
import groovy.xml.MarkupBuilder
import info.bliki.wiki.model.WikiModel
import org.simpleframework.http.Request as SimpleRequest
import org.simpleframework.http.RequestWrapper
import org.simpleframework.http.Response as SimpleResponse
import org.simpleframework.http.ResponseWrapper
import org.simpleframework.http.Status
import org.simpleframework.http.core.Container
import org.simpleframework.http.core.ContainerServer
import org.simpleframework.transport.connect.Connection
import org.simpleframework.transport.connect.SocketConnection

import java.util.logging.Logger

@CompileStatic
class GroovyScriptServer implements Container {

    static final String DEFAULT_HOST = ''
    static final int DEFAULT_PORT = 8080
    static final String DEFAULT_DOCUMENT_ROOT = 'content'
    static final String DEFAULT_HANDLER = 'main.groovy'
    static final String CONFIG = '_config.groovy'

    static final Logger log = Logger.getLogger(this.class.name)

    static void main(String[] args) {

        // Create default configuration
        ConfigObject config = new ConfigObject()
        config.putAll(
                host: DEFAULT_HOST,
                port: DEFAULT_PORT,
                documentRoot: DEFAULT_DOCUMENT_ROOT,
                handler: DEFAULT_HANDLER,
                mime: []
        )

        // Overwrite configuration with content of configuration file if exists.
        def configFile = new File(CONFIG)
        if (configFile.exists()) {
            config.merge new ConfigSlurper().parse(configFile.toURI().toURL())
        }

        // Check if document root directory exists
        def documentRootFile = new File(config.documentRoot as String).canonicalFile
        if (!documentRootFile.exists() || !documentRootFile.isDirectory()) {
            log.severe "Content directory not found: " + documentRootFile
            return
        }
        config.documentRootFile = documentRootFile

        // Find and parse handler script
        def handlerFile = new File(config.handler as String).canonicalFile
        if (!handlerFile.exists()) {
            log.severe "Cannot find handler script: " + handlerFile
            return
        }
        def handlerScriptClass = new GroovyClassLoader().parseClass(handlerFile)

        // Build and start server
        //def server = new ContainerServer(this.&handle)
        def server = new ContainerServer(new SimpleGroovyScriptServer(config,handlerScriptClass))
        def Connection connection = new SocketConnection(server)
        def address = config.host ?
                new InetSocketAddress(config.host as String, config.port as int) :
                new InetSocketAddress(config.port as int)

        try {
            connection.connect(address)
        } catch (Exception ex) {
            log.severe ex.toString()
            return
        }

        log.info "Server started at " + address
        log.info config.toProperties().toString()
    }


    final private ConfigObject _config
    final private Class _mainScriptClass

    GroovyScriptServer(ConfigObject config, Class mainScriptClass) {
        _config = config
        _mainScriptClass = mainScriptClass
    }

    // Request handler - calls main script
    void handle(SimpleRequest simpleRequest, SimpleResponse simpleResponse) {

        // Create our own request and response objects
        def request = new Request(simpleRequest, _config)
        def response = new Response(simpleResponse)

        try {
            log.info "Request: $request.method $request.path "

            // Create binding for main script
            def binding = new Binding(
                 config: _config,
                 request: request,
                 response: response,
                 out: response.printStream
            )

            // Create new instance of main script and run it
            Script script = _mainScriptClass.newInstance(binding) as Script
            script.run()

        } catch (Throwable ex) {
            response.systemError(ex)
        } finally {
            simpleResponse.close()
        }
    }
}

/**
 * Extended request object.
 */
@CompileStatic
class Request extends RequestWrapper {

    final private ConfigObject _config

    /**
     * Creates new Request object from Simple Request and server configuration.
     * @param request Request object delivered by Simple Framework.
     * @param config Server configuration from Groovy ConfigSlurper.
     */
    Request(SimpleRequest request, ConfigObject config) {
        super(request)
        _config = config
    }

    /**
     * Build File object from request path and document root.
     * Does not check if file exists or anything else.
     * @return New File instance in canonical form.
     */
    File getRequestFile() {
        def pathString = path.path
        if (pathString.startsWith('/')) pathString = pathString.substring(1)
        return new File(_config.documentRootFile as File, pathString).canonicalFile
    }

}

/**
 * Extended response object.
 */
@CompileStatic
@InheritConstructors
class Response extends ResponseWrapper {

    /**
     * Left shift operator for binary responses.
     * Sends argument back to client.
     * @param object Binary response as a byte array.
     */
    @CompileDynamic
    void leftShift(byte[] object) {
        outputStream.leftShift(object)
    }

    /**
     * Left shift operator for textual responses.
     * Sends argument back to client.
     * @param Text response as a CharSequence object.
     */
    void leftShift(CharSequence text) {
        printStream.println(text)
    }

    /**
     * Send DSL generated HTML response.
     * @param contentType Mime type of response.
     * @param closure Closure as used by Groovy's MarkupBuilder.
     */
    void sendHtml (String contentType, Closure closure) {
        this.contentType = contentType
        def builder = new MarkupBuilder(new PrintWriter(this.outputStream))
        closure.delegate = builder
        closure.call()
    }

    /**
     * Send DSL generated HTML response.
     * Content type is text/html.
     * @param closure Closure as used by Groovy's MarkupBuilder.
     */
    void sendHtml (Closure closure) {
        sendHtml 'text/html',closure
    }

    /**
     * Send a redirect response.
     * @param location URL to where the browser is to be redirected.
     */
    void redirect (String location) {
        status = Status.MOVED_PERMANENTLY
        setValue('Location',location)
    }

    /**
     * Send system error response when an exception has occurred.
     * Displays the stack track in the response body.
     * @param thr Exception
     */
    void systemError(Throwable thr) {
        reset()
        status = Status.INTERNAL_SERVER_ERROR
        thr.printStackTrace(printStream)
    }

    /**
     * Send an error response.
     * @param httpStatus HTTP status number.
     * @param text Body text.
     */
    void error (Status httpStatus, String text) {
        reset()
        status = httpStatus
        response.printStream.println(
        """ERROR $httpStatus.code $httpStatus.description

        $text
        """
        )
    }

    /**
     * Check if a content file exists.
     * If it doesn't exists, send an error response.
     * @param request Request object.
     * @return
     */
    boolean checkFileExists(Request request) {
        boolean exists = request.requestFile.exists()
        if (!exists) {
            error Status.NOT_FOUND, 'Resource not found: ' + request.path
        }
        return exists
    }
}


