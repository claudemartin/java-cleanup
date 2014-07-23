package ch.claude_martin.cleanup;

import static java.util.Arrays.asList;
import static java.util.Collections.synchronizedList;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;


import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Tests for {@link Cleanup}.
 *
 */
@SuppressWarnings("static-method")
public class CleanupTest {
  
  static volatile Throwable exception = null;
  @BeforeClass
  public static void beforeClass() {
    Example.logger.setLevel(Level.OFF);
    CleanupDaemon.THREAD.setPriority(Thread.NORM_PRIORITY+1); // only while testing
    CleanupDaemon.addExceptionHandler((ex) -> {
      if(ex instanceof TestException)
        return; // see testExceptionHandler()
      exception = ex;
      fail("Exception: " + ex);
    });
  }
  
  @Before
  public void before() {
    exception = null;
  }
  @After
  public void after() throws Throwable {
    if(exception != null)
      throw exception;
  }

  /** "cleanupable" is now a word. */
  private static final class MyCleanupable implements Cleanup {

    public MyCleanupable() {
      super();
    }

    @SuppressWarnings("unused")
    public final byte[] data = new byte[5_000_000];

    public final Object getAnonymous() {
      return new Cloneable() {
        // this$0 = implicit reference to MyCleanupable.this
      };
    }
  }

  @Test
  public final void testCleanup() {
    final int answer = 42;
    final AtomicInteger i = new AtomicInteger(0);
    {
      MyCleanupable test = new MyCleanupable();
      test.registerCleanup((v) -> {
        i.set(v);
      }, answer);
      test = null;
    }
    gc();
    Assert.assertTrue(answer == i.get());
  }

  private static void gc() {
    for (int j = 0; j < 20; j++) {
      Thread.yield();
      System.gc();
      System.runFinalization();
    }
  }

  @Test
  public final void testTwice() {
    final AtomicInteger i = new AtomicInteger(0);
    MyCleanupable test = new MyCleanupable();
    test.registerCleanup((v) -> {
      i.incrementAndGet();
    }, 42);
    test.registerCleanup((v) -> {
      i.incrementAndGet();
    }, -1);
    test = null;
    gc();
    assertEquals(2, i.get());
  }

  @Test(expected = IllegalArgumentException.class)
  public final void testThis() {
    final MyCleanupable test = new MyCleanupable();
    test.registerCleanup((v) -> {
    }, test);
  }

  @Test(expected = IllegalArgumentException.class)
  public final void testAnonymous() {
    final MyCleanupable test = new MyCleanupable();
    test.registerCleanup((v) -> {
    }, test.getAnonymous());
  }

  @Test(expected = IllegalArgumentException.class)
  public final void testLambda() {
    final MyCleanupable test = new MyCleanupable();
    test.registerCleanup((v) -> {
    }, Function.identity());
  }

  @Test
  public final void testArray() {
    final MyCleanupable test = new MyCleanupable();
    test.registerCleanup((v) -> {
    }, new byte[10]);
  }

  @Test
  public final void testStatic() {
    Object test = new String("test");
    final AtomicBoolean result = new AtomicBoolean(false);
    Cleanup.registerCleanup(test, (v) -> {
      result.set(true);
    }, new byte[10]);
    test = null;
    gc();
    assertTrue(result.get());
  }

  @Test
  public final void testParallel() throws InterruptedException {
    final AtomicBoolean result = new AtomicBoolean(true);
    final ExecutorService pool = Executors.newFixedThreadPool(4, (r) -> {
      final Thread thread = new Thread(r);
      thread.setName("testMany");
      thread.setUncaughtExceptionHandler((t, ex) -> {
        result.set(false);
        exception = ex;
      });
      return thread;
    });
    for (int i = 0; i < 8; i++) {
      pool.execute(() -> {
        try {
          this.testCleanup();
        } catch (final Throwable e) {
          result.set(false);
        }
      });
    }
    pool.shutdown();
    pool.awaitTermination(10, TimeUnit.MINUTES);
    gc();
    Thread.sleep(100);
    assertTrue(result.get());
  }

  @Test
  public final void testNullValue() throws Exception {
    final AtomicBoolean result = new AtomicBoolean(false);

    Example example = new Example();
    example.registerCleanup(() -> {
      result.set(true);
    });

    example = null;
    gc();
    assertTrue(result.get());
  }

  @Test(expected=NullPointerException.class)
  public final void testNullLambda() throws Exception {
    Example example = new Example();
    example.registerCleanup(null, -1);
    fail("null should not be allowed.");
  }

  @Test
  public final void testExceptionHandler() throws Exception {
    {
      final List<String> refs = synchronizedList(new ArrayList<>());
      final String message = "test";
      MyCleanupable test = new MyCleanupable();
      final Consumer<Throwable> c = (t) -> {
        refs.add(t.getMessage());
      };
      Cleanup.addExceptionHandler(c);
      Cleanup.addExceptionHandler(c);
      Cleanup.addExceptionHandler(c);
      test.registerCleanup((v) -> {
        throw new TestException(message);
      }, 42);
      test = null;
      gc();
      assertEquals(asList(message, message, message), refs);
    }
    
    {
      AtomicInteger i = new AtomicInteger(0);
      for (int j = 0; j < 10; j++) {
        Cleanup.addExceptionHandler((e) -> {
          i.getAndIncrement();
          throw new NullPointerException();
        });
      }
      new MyCleanupable().registerCleanup((v) -> {
        throw new TestException("test");
      }, 42);
      gc();
      // First increments, others are not invoked:
      assertEquals(1, i.get());
    }
  }
  
  /** Used to test Exceptions in {@link #testExceptionHandler()}. */
  @SuppressWarnings("serial")
  private static final class TestException extends RuntimeException {
    public TestException(final String message) {
      super(message);
    }
  }
  
  @Test
  public void testRunCleanup() throws Exception {
    final int n = 20;
    final AtomicInteger ai = new AtomicInteger(0);
    
    for (int i = 0; i < n; i++)
      new Cleanup() { }.registerCleanup(() -> {
        try {
          Thread.sleep(50);
        } catch (Exception e) {
          exception = e;
        } finally {
          ai.getAndIncrement();
        }
      });
    gc();
    assumeTrue(ai.get()<n);
    Cleanup.runCleanup();
    // There's a chance that one is still processing.
    if(ai.get()<n)
      Thread.sleep(100);
    assertEquals(n, ai.get());
  }
  
  @Test
  @Ignore // Can't be tested by JUnit.
  public void testRunCleanupOnExit() throws Exception {
    // unignore and run this single test:
    Cleanup.runCleanupOnExit(true);
    new Cleanup() { }.registerCleanup(() -> {
        System.out.println("runCleanupOnExit works fine!");
    });
  }
  
}
