package io.github.bags156.messagecenter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A message as a task: its first line as the title, the whole message and who sent it as notes; any task app recognized. */
public class TaskAppsTest {
    @Test public void aMessageBecomesATask() {
        assertArrayEquals(new String[]{"Can you pick up the cake?", "Can you pick up the cake?\nIt's at Walmart\n\n— Hannah, Nov 25 at 3:10 PM (Message Center)"},
            TaskApps.task("  Can you pick up the cake?\nIt's at Walmart ", "Message", "Hannah", "Nov 25 at 3:10 PM"));
        assertEquals("a photo on its own says what it is and who sent it", "Photo from Mike", TaskApps.task("", "Photo", "Mike", "Nov 24 at 9:00 AM")[0]);
        String longOne = "x".repeat(150);
        assertEquals(100, TaskApps.task(longOne, "Message", "Mike", "")[0].codePointCount(0, TaskApps.task(longOne, "Message", "Mike", "")[0].length()));
        assertTrue(TaskApps.task(longOne, "Message", "Mike", "")[0].endsWith("…"));
        assertEquals("— You (Message Center)", TaskApps.task("", "Message", "You", "")[1]);
    }

    @Test public void taskAppsAreRecognized() {
        assertTrue(TaskApps.taskLike("com.google.android.apps.tasks", "Tasks"));
        assertTrue(TaskApps.taskLike("com.todoist", "Todoist"));
        assertTrue(TaskApps.taskLike("com.example.mylist", "My To-Do List"));
        assertTrue(TaskApps.taskLike("com.example.r", "Reminders"));
        assertFalse(TaskApps.taskLike("com.example.chat", "Chat"));
        assertFalse(TaskApps.taskLike("com.example.chat", null));
    }
}
