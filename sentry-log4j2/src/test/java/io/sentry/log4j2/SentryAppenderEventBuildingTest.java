package io.sentry.log4j2;

import io.sentry.BaseTest;
import io.sentry.Sentry;
import io.sentry.SentryClient;
import io.sentry.event.interfaces.*;
import mockit.Injectable;
import mockit.NonStrictExpectations;
import mockit.Tested;
import mockit.Verifications;
import io.sentry.event.Event;
import io.sentry.event.EventBuilder;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.FormattedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.spi.DefaultThreadContextStack;
import org.hamcrest.Matchers;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class SentryAppenderEventBuildingTest extends BaseTest {
    @Tested
    private SentryAppender sentryAppender = null;
    private MockUpErrorHandler mockUpErrorHandler;
    @Injectable
    private SentryClient mockSentryClient = null;
    private String mockExtraTag = "d421627f-7a25-4d43-8210-140dfe73ff10";
    private Set<String> extraTags;

    @BeforeMethod
    public void setUp() throws Exception {
        Sentry.setStoredClient(mockSentryClient);
        sentryAppender = new SentryAppender();
        mockUpErrorHandler = new MockUpErrorHandler();
        sentryAppender.setHandler(mockUpErrorHandler.getMockInstance());
        extraTags = new HashSet<>();
        extraTags.add(mockExtraTag);
    }

    private void assertNoErrorsInErrorHandler() throws Exception {
        assertThat(mockUpErrorHandler.getErrorCount(), is(0));
    }

    @Test
    public void testSimpleMessageLogging() throws Exception {
        final String loggerName = "0a05c9ff-45ef-45cf-9595-9307b0729a0d";
        final String message = "6ff10df4-2e27-43f5-b4e9-a957f8678176";
        final String threadName = "f891f3c4-c619-4441-9c47-f5c8564d3c0a";
        final Date date = new Date(1373883196416L);

        sentryAppender.append(new Log4jLogEvent(loggerName, null, null, Level.INFO, new SimpleMessage(message),
                null, null, null, threadName, null, date.getTime()));

        new Verifications() {{
            EventBuilder eventBuilder;
            mockSentryClient.sendEvent(eventBuilder = withCapture());
            Event event = eventBuilder.build();
            assertThat(event.getMessage(), is(message));
            assertThat(event.getLogger(), is(loggerName));
            assertThat(event.getExtra(), Matchers.<String, Object>hasEntry(SentryAppender.THREAD_NAME, threadName));
            assertThat(event.getTimestamp(), is(date));
            assertThat(event.getSdk().getIntegrations(), contains("log4j2"));
        }};
        assertNoErrorsInErrorHandler();
    }

    @DataProvider(name = "levels")
    private Object[][] levelConversions() {
        return new Object[][]{
                {Event.Level.DEBUG, Level.TRACE},
                {Event.Level.DEBUG, Level.DEBUG},
                {Event.Level.INFO, Level.INFO},
                {Event.Level.WARNING, Level.WARN},
                {Event.Level.ERROR, Level.ERROR},
                {Event.Level.FATAL, Level.FATAL}};
    }

    @Test(dataProvider = "levels")
    public void testLevelConversion(final Event.Level expectedLevel, Level level) throws Exception {
        sentryAppender.append(new Log4jLogEvent(null, null, null, level, new SimpleMessage(""), null));

        new Verifications() {{
            EventBuilder eventBuilder;
            mockSentryClient.sendEvent(eventBuilder = withCapture());
            Event event = eventBuilder.build();
            assertThat(event.getLevel(), is(expectedLevel));
        }};
        assertNoErrorsInErrorHandler();
    }

    @Test
    public void testExceptionLogging() throws Exception {
        final Exception exception = new Exception("d0d1b31f-e885-42e3-aac6-48c500f10ed1");

        sentryAppender.append(new Log4jLogEvent(null, null, null, Level.ERROR, new SimpleMessage(""), exception));

        new Verifications() {{
            EventBuilder eventBuilder;
            mockSentryClient.sendEvent(eventBuilder = withCapture());
            Event event = eventBuilder.build();
            ExceptionInterface exceptionInterface = (ExceptionInterface) event.getSentryInterfaces()
                    .get(ExceptionInterface.EXCEPTION_INTERFACE);
            SentryException sentryException = exceptionInterface.getExceptions().getFirst();
            assertThat(sentryException.getExceptionMessage(), is(exception.getMessage()));
            assertThat(sentryException.getStackTraceInterface().getStackTrace(),
                is(SentryStackTraceElement.fromStackTraceElements(exception.getStackTrace(), null)));
        }};
        assertNoErrorsInErrorHandler();
    }

    @Test
    public void testLogParametrisedMessage() throws Exception {
        final String messagePattern = "Formatted message {} {} {}";
        final Object[] parameters = {"first parameter", new Object[0], null};

        sentryAppender.append(new Log4jLogEvent(null, null, null, Level.INFO,
                new FormattedMessage(messagePattern, parameters), null));

        new Verifications() {{
            EventBuilder eventBuilder;
            mockSentryClient.sendEvent(eventBuilder = withCapture());
            Event event = eventBuilder.build();
            MessageInterface messageInterface = (MessageInterface) event.getSentryInterfaces()
                    .get(MessageInterface.MESSAGE_INTERFACE);
            assertThat(event.getMessage(), is("Formatted message first parameter [] null"));
            assertThat(messageInterface.getMessage(), is(messagePattern));
            assertThat(messageInterface.getParameters(),
                    is(Arrays.asList(parameters[0].toString(), parameters[1].toString(), null)));
        }};
        assertNoErrorsInErrorHandler();
    }

    @Test
    public void testMarkerAddedToTag() throws Exception {
        final String markerName = "c97e1fc0-9fff-41b3-8d0d-c24b54c670bb";

        sentryAppender.append(new Log4jLogEvent(null, MarkerManager.getMarker(markerName), null, Level.INFO,
                new SimpleMessage(""), null));

        new Verifications() {{
            EventBuilder eventBuilder;
            mockSentryClient.sendEvent(eventBuilder = withCapture());
            Event event = eventBuilder.build();
            assertThat(event.getTags(), Matchers.<String, Object>hasEntry(SentryAppender.LOG4J_MARKER, markerName));
        }};
        assertNoErrorsInErrorHandler();
    }

    @Test
    public void testMdcAddedToExtra() throws Exception {
        final String extraKey = "a4ce2632-8d9c-471d-8b06-1744be2ae8e9";
        final String extraValue = "6dbeb494-197e-4f57-939a-613e2c16607d";

        sentryAppender.append(new Log4jLogEvent(null, null, null, Level.INFO, new SimpleMessage(""), null,
                Collections.singletonMap(extraKey, extraValue), null, null, null, 0));

        new Verifications() {{
            EventBuilder eventBuilder;
            mockSentryClient.sendEvent(eventBuilder = withCapture());
            Event event = eventBuilder.build();
            assertThat(event.getExtra(), Matchers.<String, Object>hasEntry(extraKey, extraValue));
        }};
        assertNoErrorsInErrorHandler();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testNdcAddedToExtra() throws Exception {
        final ThreadContext.ContextStack contextStack = new DefaultThreadContextStack(true);
        contextStack.push("444af01f-fb80-414f-b035-15bdb91cb8b2");
        contextStack.push("a1cb5e08-480a-4b32-b675-212f00c44e05");
        contextStack.push("0aa5db14-1579-46ef-aae2-350d974e7fb8");

        sentryAppender.append(new Log4jLogEvent(null, null, null, Level.INFO, new SimpleMessage(""), null, null,
                contextStack, null, null, 0));

        new Verifications() {{
            EventBuilder eventBuilder;
            mockSentryClient.sendEvent(eventBuilder = withCapture());
            Event event = eventBuilder.build();
            assertThat((List<String>) event.getExtra().get(SentryAppender.LOG4J_NDC), equalTo(contextStack.asList()));
        }};
        assertNoErrorsInErrorHandler();
    }

    @Test
    public void testSourceUsedAsStacktrace() throws Exception {
        final StackTraceElement location = new StackTraceElement("7039c1f7-21e3-4134-8ced-524281633224",
                "c68f3af9-1618-4d80-ad1b-ea0701568153", "f87a8821-1c70-44b8-81c3-271d454e4b08", 42);

        sentryAppender.append(new Log4jLogEvent(null, null, null, Level.INFO, new SimpleMessage(""), null, null, null,
                null, location, 0));

        new Verifications() {{
            EventBuilder eventBuilder;
            mockSentryClient.sendEvent(eventBuilder = withCapture());
            Event event = eventBuilder.build();
            StackTraceInterface stackTraceInterface = (StackTraceInterface) event.getSentryInterfaces()
                    .get(StackTraceInterface.STACKTRACE_INTERFACE);
            assertThat(stackTraceInterface.getStackTrace(), arrayWithSize(1));
            assertThat(stackTraceInterface.getStackTrace()[0], is(SentryStackTraceElement.fromStackTraceElement(location)));
        }};
        assertNoErrorsInErrorHandler();
    }

    @Test
    public void testExtraTagObtainedFromMdc() throws Exception {
        Map<String, String> mdc = new HashMap<>();
        mdc.put(mockExtraTag, "565940d2-f4a4-42f6-9496-42e3c7c85c43");
        mdc.put("other_property", "395856e8-fa1d-474f-8fa9-c062b4886527");

        new NonStrictExpectations() {{
            mockSentryClient.getMdcTags();
            result = extraTags;
        }};

        sentryAppender.append(new Log4jLogEvent(null, null, null, Level.INFO, new SimpleMessage(""), null, mdc, null, null, null, 0));

        new Verifications() {{
            EventBuilder eventBuilder;
            mockSentryClient.sendEvent(eventBuilder = withCapture());
            Event event = eventBuilder.build();
            assertThat(event.getTags().entrySet(), hasSize(1));
            assertThat(event.getTags(), hasEntry(mockExtraTag, "565940d2-f4a4-42f6-9496-42e3c7c85c43"));
            assertThat(event.getExtra(), not(hasKey(mockExtraTag)));
            assertThat(event.getExtra(), Matchers.<String, Object>hasEntry("other_property", "395856e8-fa1d-474f-8fa9-c062b4886527"));
        }};
        assertNoErrorsInErrorHandler();
    }
}
