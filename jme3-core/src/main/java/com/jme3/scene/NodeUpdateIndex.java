package com.jme3.scene;

import com.jme3.bounding.BoundingVolume;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.BitSet;

/** Runtime-only dirty-child index and balanced bound reduction tree for one Node. */
final class NodeUpdateIndex implements SceneChangeListener {
    private final Node owner;
    private final Map<Spatial, Integer> positions = new IdentityHashMap<>();
    private final Set<Spatial> dirty = Collections.newSetFromMap(new IdentityHashMap<>());
    private BoundingVolume[] tree;
    private int leaves;
    private boolean full = true;
    private final BitSet dirtyAncestors = new BitSet();

    NodeUpdateIndex(Node owner) {
        this.owner = owner;
        owner.addSceneChangeListener(this);
    }

    @Override
    public void sceneChanged(Spatial spatial, Change change) {
        if (change == Change.USER_DATA) {
            return;
        }
        if (spatial == owner || change == Change.ATTACHED || change == Change.DETACHING) {
            full = true;
            return;
        }
        Spatial child = spatial;
        while (child.getParent() != null && child.getParent() != owner) {
            child = child.getParent();
        }
        if (child.getParent() == owner) {
            dirty.add(child);
        }
    }

    Spatial[] childrenToUpdate(boolean inheritedStateChanged) {
        if (inheritedStateChanged) {
            full = true;
        }
        return full ? owner.children.getArray() : dirty.toArray(new Spatial[dirty.size()]);
    }

    BoundingVolume updateBound(BoundingVolume previous) {
        if (full || tree == null) {
            positions.clear();
            Spatial[] children = owner.children.getArray();
            leaves = 1;
            while (leaves < children.length) {
                leaves *= 2;
            }
            tree = new BoundingVolume[leaves * 2];
            for (int i = 0; i < children.length; i++) {
                positions.put(children[i], leaves + i);
                BoundingVolume bound = children[i].getWorldBound();
                tree[leaves + i] = bound == null ? null : bound.clone();
            }
            for (int i = leaves - 1; i > 0; i--) {
                merge(i);
            }
        } else {
            for (Spatial child : dirty) {
                Integer position = positions.get(child);
                if (position == null) {
                    continue;
                }
                BoundingVolume bound = child.getWorldBound();
                tree[position] = bound == null ? null : bound.clone(tree[position]);
                for (int i = position / 2; i > 0; i /= 2) {
                    if (dirtyAncestors.get(i)) {
                        break;
                    }
                    dirtyAncestors.set(i);
                }
            }
            // Parents have lower indices. Recompute shared ancestors once, after
            // both children, instead of merging the root for every moving leaf.
            for (int i = dirtyAncestors.length() - 1; i > 0; i = dirtyAncestors.previousSetBit(i - 1)) {
                merge(i);
            }
            dirtyAncestors.clear();
        }
        return tree[1] == null ? null : tree[1].clone(previous);
    }

    private void merge(int index) {
        BoundingVolume left = tree[index * 2];
        BoundingVolume right = tree[index * 2 + 1];
        if (left == null) {
            tree[index] = right == null ? null : right.clone(tree[index]);
        } else {
            tree[index] = left.clone(tree[index]);
            if (right != null) {
                tree[index].mergeLocal(right);
            }
        }
    }

    void finishUpdate() {
        // If bounds were not requested (for example a light-only update), preserve
        // the full invalidation until a bound tree has actually been constructed.
        if (tree != null) {
            full = false;
        }
        dirty.clear();
    }

    void close() {
        owner.removeSceneChangeListener(this);
    }
}
