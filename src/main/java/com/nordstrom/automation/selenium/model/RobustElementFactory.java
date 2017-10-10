package com.nordstrom.automation.selenium.model;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.WebDriver.Timeouts;
import org.openqa.selenium.internal.FindsByCssSelector;
import org.openqa.selenium.internal.FindsByXPath;
import com.nordstrom.automation.selenium.SeleniumConfig.WaitType;
import com.nordstrom.automation.selenium.core.ByType;
import com.nordstrom.automation.selenium.core.JsUtility;
import com.nordstrom.automation.selenium.core.WebDriverUtils;
import com.nordstrom.automation.selenium.exceptions.OptionalElementNotAcquiredException;
import com.nordstrom.automation.selenium.interfaces.WrapsContext;
import com.nordstrom.automation.selenium.support.Coordinator;
import com.nordstrom.common.base.UncheckedThrow;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.FieldValue;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.Pipe;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.This;
import static net.bytebuddy.matcher.ElementMatchers.*;

public class RobustElementFactory {
    
    /** wraps 1st matched reference */
    public static final int CARDINAL = -1;
    /** wraps an optional reference */
    public static final int OPTIONAL = -2;
    
    private static Map<String, InstanceCreator> creatorMap = new HashMap<>();
    
    private RobustElementFactory() {
        throw new AssertionError("RobustElementFactory is a static utility class that cannot be instantiated");
    }
    
    /**
     * Basic robust web element constructor
     * 
     * @param context element search context
     * @param locator element locator
     */
    public static WebElement makeRobustElement(WrapsContext context, By locator) {
        return makeRobustElement(null, context, locator, CARDINAL);
    }
    
    /**
     * Constructor for wrapping an existing element reference 
     * 
     * @param element element reference to be wrapped
     * @param context element search context
     * @param locator element locator
     */
    public static WebElement makeRobustElement(WebElement element, WrapsContext context, By locator) {
        return makeRobustElement(element, context, locator, CARDINAL);
    }
    
    /**
     * Main robust web element constructor
     * 
     * @param element element reference to be wrapped (may be 'null')
     * @param context element search context
     * @param locator element locator
     * @param index element index
     */
    public static WebElement makeRobustElement(WebElement element, WrapsContext context, By locator, int index) {
        InstanceCreator creator = getCreator(context);
        ElementMethodInterceptor interceptor = new ElementMethodInterceptor(element, context, locator, index);
        WebElement robust = (WebElement) creator.makeInstance();
        ((InterceptionAccessor) robust).setInterceptor(interceptor);
        return robust;
    }
    
    private static synchronized InstanceCreator getCreator(WrapsContext context) {
        WebDriver driver = context.getWrappedDriver();
        String driverName = driver.getClass().getName();
        if (creatorMap.containsKey(driverName)) {
            return creatorMap.get(driverName);
        }
        
        WebElement reference = driver.findElement(By.tagName("*"));
        Class<? extends WebElement> refClass = reference.getClass();
        
        Class<? extends WebElement> wrapperClass = new ByteBuddy()
                        .subclass(refClass)
                        .name(refClass.getPackage().getName() + ".Robust" + refClass.getSimpleName())
                        .method(not(isDeclaredBy(Object.class)))
                        .intercept(MethodDelegation.toField("interceptor"))
                        .implement(RobustWebElement.class)
                        .defineField("interceptor", ElementMethodInterceptor.class, Visibility.PRIVATE)
                        .implement(InterceptionAccessor.class).intercept(FieldAccessor.ofBeanProperty())
                        .make()
                        .load(refClass.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
                        .getLoaded();
        
        InstanceCreator creator;
        
        try {
            creator = new ByteBuddy()
                            .subclass(InstanceCreator.class)
                            .method(not(isDeclaredBy(Object.class)))
                            .intercept(MethodDelegation.toConstructor(wrapperClass))
                            .make()
                            .load(wrapperClass.getClassLoader())
                            .getLoaded().newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw UncheckedThrow.throwUnchecked(e);
        }
        
        creatorMap.put(driverName, creator);
        return creator;
    }
    
    public interface InterceptionAccessor {
        ElementMethodInterceptor getInterceptor();
        void setInterceptor(ElementMethodInterceptor interceptor);
    }
    
    public interface InstanceCreator {
        Object makeInstance();
    }
    
    public static class ElementMethodInterceptor implements ReferenceFetcher {
        
        /**
         * This is the method that intercepts component container methods in "enhanced" model objects.
         * 
         * @param obj "enhanced" object upon which the method was invoked
         * @param method {@link Method} object for the invoked method
         * @param args method invocation arguments
         * @return {@code anything} (the result of invoking the intercepted method)
         * @throws Exception {@code anything} (exception thrown by the intercepted method)
         */
        @RuntimeType
        public Object intercept(@This Object obj, @Origin Method method, @AllArguments Object[] args) throws Exception
        {
            try {
                return method.invoke(getWrappedElement(), args);
            } catch (InvocationTargetException ite) {
                Throwable t = ite.getCause();
                if (t instanceof StaleElementReferenceException) {
                    try {
                        StaleElementReferenceException sere = (StaleElementReferenceException) t;
                        return method.invoke(refreshReference(sere).getWrappedElement(), args);
                    } catch (NullPointerException npe) {
                        throw deferredException();
                    }
                }
                throw UncheckedThrow.throwUnchecked(t);
            }
        }
        
        @RuntimeType
        public static Object intercept(@Pipe Function<Object, Object> pipe,
                        @FieldValue("referenceFetcher") Object delegate) throws Exception {
            return pipe.apply(delegate);
        }
        
        private static final String LOCATE_BY_CSS = JsUtility.getScriptResource("locateByCss.js");
        private static final String LOCATE_BY_XPATH = JsUtility.getScriptResource("locateByXpath.js");
        
        private enum Strategy { LOCATOR, JS_XPATH, JS_CSS }
        
        private final WebDriver driver;
        private final WrapsContext context;
        private WebElement wrapped;
        private By locator;
        private int index;
        
        private String selector;
        private Strategy strategy = Strategy.LOCATOR;
        
        private Long acquiredAt;
        
        private NoSuchElementException deferredException;
        
        private final boolean findsByCssSelector;
        private final boolean findsByXPath;
        
        /**
         * Main robust web element constructor
         * 
         * @param element element reference to be wrapped (may be 'null')
         * @param context element search context
         * @param locator element locator
         * @param index element index
         */
        public ElementMethodInterceptor(WebElement element, WrapsContext context, By locator, int index) {
            
            // if specified element is already robust
            if (element instanceof ElementMethodInterceptor) {
                ElementMethodInterceptor robust = (ElementMethodInterceptor) element;
                this.acquiredAt = robust.acquiredAt;
                
                this.wrapped = robust.wrapped;
                this.context = robust.context;
                this.locator = robust.locator;
                this.index = robust.index;
            } else {
                Objects.requireNonNull(context, "[context] must be non-null");
                Objects.requireNonNull(locator, "[locator] must be non-null");
                if (index < OPTIONAL) {
                    throw new IndexOutOfBoundsException("Specified index is invalid");
                }
                
                this.wrapped = element;
                this.context = context;
                this.locator = locator;
                this.index = index;
            }
            
            driver = WebDriverUtils.getDriver(this.context.getWrappedContext());
            
            findsByCssSelector = (driver instanceof FindsByCssSelector);
            findsByXPath = (driver instanceof FindsByXPath);
            
            if ((this.index == OPTIONAL) || (this.index > 0)) {
                if (findsByXPath && ( ! (this.locator instanceof By.ByCssSelector))) {
                    selector = ByType.xpathLocatorFor(this.locator);
                    if (this.index > 0) {
                        selector += "[" + (this.index + 1) + "]";
                    }
                    strategy = Strategy.JS_XPATH;
                    
                    this.locator = By.xpath(this.selector);
                } else if (findsByCssSelector) {
                    selector = ByType.cssLocatorFor(this.locator);
                    if (selector != null) {
                        strategy = Strategy.JS_CSS;
                    }
                }
            }
            
            if (this.wrapped == null) {
                if (this.index == OPTIONAL) {
                    acquireReference(this);
                } else {
                    refreshReference(null);
                }
            } else if (acquiredAt == null) {
                acquiredAt = Long.valueOf(System.currentTimeMillis());
            }
        }
        
        /**
         * {@inheritDoc}
         */
        @Override
        public WebElement getWrappedElement() {
            if (wrapped == null) {
                refreshReference(null);
            }
            return wrapped;
        }
        
        /**
         * Determine if this robust element wraps a valid reference.
         * 
         * @return 'true' if reference was acquired; otherwise 'false'
         */
        @Override
        public boolean hasReference() {
            if ((index == OPTIONAL) && (wrapped == null)) {
                acquireReference(this);
                return (null != wrapped);
            } else {
                return true;
            }
        }
        
        /**
         * Get the search context for this element.
         * 
         * @return element search context
         */
        @Override
        public WrapsContext getContext() {
            return context;
        }
        
        /**
         * Get the locator for this element.
         * 
         * @return element locator
         */
        @Override
        public By getLocator() {
            return locator;
        }
        
        /**
         * Get the element index.
         * <p>
         * <b>NOTE</b>: {@link #CARDINAL} = 1st matched reference; {@link #OPTIONAL} = an optional reference
         * 
         * @return element index (see NOTE)
         */
        @Override
        public int getIndex() {
            return index;
        }
        
        /**
         * Refresh the wrapped element reference.
         * 
         * @param refreshTrigger {@link StaleElementReferenceException} that necessitates reference refresh
         * @return this robust web element with refreshed reference
         */
        @Override
        public ElementMethodInterceptor refreshReference(final StaleElementReferenceException refreshTrigger) {
            try {
                WaitType.IMPLIED.getWait((SearchContext) context).until(referenceIsRefreshed(this));
                return this;
            } catch (TimeoutException e) {
                if (refreshTrigger == null) {
                    throw UncheckedThrow.throwUnchecked(e.getCause());
                }
            } catch (WebDriverException e) {
                if (refreshTrigger == null) {
                    throw e;
                }
            }
            throw refreshTrigger;
        }
        
        /**
         * Returns a 'wait' proxy that refreshes the wrapped reference of the specified robust element.
         * 
         * @param element robust web element object
         * @return wrapped element reference (refreshed)
         */
        private static Coordinator<ElementMethodInterceptor> referenceIsRefreshed(final ElementMethodInterceptor element) {
            return new Coordinator<ElementMethodInterceptor>() {

                @Override
                public ElementMethodInterceptor apply(SearchContext context) {
                    try {
                        return acquireReference(element);
                    } catch (StaleElementReferenceException e) {
                        ((WrapsContext) context).refreshContext(((WrapsContext) context).acquiredAt());
                        return acquireReference(element);
                    }
                }

                @Override
                public String toString() {
                    return "element reference to be refreshed";
                }
            };
            
        }
        
        /**
         * Acquire the element reference that's wrapped by the specified robust element.
         * 
         * @param element robust web element object
         * @return wrapped element reference
         */
        private static ElementMethodInterceptor acquireReference(ElementMethodInterceptor element) {
            SearchContext context = element.context.getWrappedContext();
            
            if (element.strategy == Strategy.LOCATOR) {
                Timeouts timeouts = element.driver.manage().timeouts().implicitlyWait(0, TimeUnit.SECONDS);
                try {
                    if (element.index > 0) {
                        List<WebElement> elements = context.findElements(element.locator);
                        if (element.index < elements.size()) {
                            element.wrapped = elements.get(element.index);
                        } else {
                            throw new NoSuchElementException(
                                    String.format("Too few elements located %s: need: %d; have: %d", 
                                            element.locator, element.index + 1, elements.size()));
                        }
                    } else {
                        element.wrapped = context.findElement(element.locator);
                    }
                } catch (NoSuchElementException e) {
                    if (element.index != OPTIONAL) {
                        throw e;
                    }
                    
                    element.deferredException = e;
                    element.wrapped = null;
                } finally {
                    timeouts.implicitlyWait(WaitType.IMPLIED.getInterval(), TimeUnit.SECONDS);
                }
            } else {
                List<Object> args = new ArrayList<>();
                List<ElementMethodInterceptor> contextArg = new ArrayList<>();
                if (context instanceof ElementMethodInterceptor) {
                    contextArg.add((ElementMethodInterceptor) context);
                }
                
                String js;
                args.add(contextArg);
                args.add(element.selector);
                
                if (element.strategy == Strategy.JS_XPATH) {
                    js = LOCATE_BY_XPATH;
                } else {
                    js = LOCATE_BY_CSS;
                    args.add(element.index);
                }
                
                element.wrapped = JsUtility.runAndReturn(element.driver, js, args.toArray());
            }
            
            if (element.wrapped != null) {
                element.acquiredAt = System.currentTimeMillis();
                element.deferredException = null;
            }
            
            return element;
        }
        
        /**
         * {@inheritDoc}
         */
        @Override
        public SearchContext getWrappedContext() {
            return getWrappedElement();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public SearchContext refreshContext(Long expiration) {
            // refresh wrapped element reference if it's past the expiration
            return (expiration.compareTo(acquiredAt()) >= 0) ? refreshReference(null) : this;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Long acquiredAt() {
            return acquiredAt;
        }
        
        /**
         * {@inheritDoc}
         */
        @Override
        public WebDriver getWrappedDriver() {
            return driver;
        }
        
        /**
         * Get a wrapped reference to the first element matching the specified locator.
         * <p>
         * <b>NOTE</b>: Use {@link ElementMethodInterceptor#hasReference()} to determine if a valid reference was acquired.
         * 
         * @param by the locating mechanism
         * @return robust web element
         */
        public WebElement findOptional(By by) {
            return getElement(this, by, OPTIONAL);
        }

        /**
         * Get the list of elements that match the specified locator in the indicated context.
         * 
         * @param context element search context
         * @param locator element locator
         * @return list of robust elements in context that match the locator
         */
        public static List<WebElement> getElements(WrapsContext context, By locator) {
            List<WebElement> elements;
            try {
                elements = context.getWrappedContext().findElements(locator);
                for (int index = 0; index < elements.size(); index++) {
                    elements.set(index, RobustElementFactory.makeRobustElement(elements.get(index), context, locator, index));
                }
            } catch (StaleElementReferenceException e) {
                elements = context.refreshContext(context.acquiredAt()).findElements(locator);
            }
            return elements;
        }
        
        /**
         * Get the first element that matches the specified locator in the indicated context.
         * 
         * @param context element search context
         * @param locator element locator
         * @return robust element in context that matches the locator
         */
        public static WebElement getElement(WrapsContext context, By locator) {
            return getElement(context, locator, CARDINAL);
        }
        
        /**
         * Get the item at the specified index in the list of elements matching the specified 
         * locator in the indicated context.
         * 
         * @param context element search context
         * @param locator element locator
         * @param index element index
         * @return indexed robust element in context that matches the locator
         */
        public static WebElement getElement(WrapsContext context, By locator, int index) {
            return RobustElementFactory.makeRobustElement(null, context, locator, index);
        }
        
        /**
         * Throw the deferred exception that was stored upon failing to acquire the reference for an optional element.<br>
         * <p>
         * <b>NOTE</b>:
         * The deferred exception is not thrown directly - it's wrapped in a OptionalElementNotAcquiredException to
         * indicate that the failure was caused by utilizing an optional element for which no actual reference could be
         * acquired.
         * 
         * @return nothing (always throws deferred exception wrapped in OptionalElementNotAcquiredException)
         */
        private OptionalElementNotAcquiredException deferredException() {
            throw new OptionalElementNotAcquiredException(deferredException);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public SearchContext switchTo() {
            return context.switchTo();
        }
        
        /**
         * {@inheritDoc}
         */
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + context.hashCode();
            result = prime * result + locator.hashCode();
            result = prime * result + index;
            return result;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ElementMethodInterceptor other = (ElementMethodInterceptor) obj;
            if (!context.equals(other.context))
                return false;
            if (!locator.equals(other.locator))
                return false;
            if (index != other.index)
                return false;
            return true;
        }

        @Override
        public WebElement findElement(By locator) {
            return ElementMethodInterceptor.getElement(this, locator);
        }

        @Override
        public List<WebElement> findElements(By locator) {
            return ElementMethodInterceptor.getElements(this, locator);
        }
    }
}
