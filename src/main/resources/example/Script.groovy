import groovy.transform.Field

@Field int count0 = 0

def interact(e) {
    e.canceled = true
    int tc;
    try {
        tc = count++
    } catch (MissingPropertyException ignored) {
        tc = count0++
    }
    e.npc.say "$tc"
}

static def cacheScript() {
    "Test:0"
}