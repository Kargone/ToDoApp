package com.karson.todoapp.data;

import android.content.Context;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

/**
 * Chooses the task backend the same way everywhere: Firebase when a user is signed in, local JSON
 * otherwise. Mirrors {@code MainActivity.createRepository()} so the widgets and the activity agree.
 */
public final class TaskRepositories {

    private TaskRepositories() {}

    public static TaskRepository create(Context context) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            return new FirebaseTaskRepository(user.getUid());
        }
        return new LocalTaskRepository(context.getApplicationContext());
    }
}
