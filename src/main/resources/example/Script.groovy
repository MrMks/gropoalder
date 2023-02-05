package example

import groovy.transform.Field

@Field int count = 0

def interact(e) {
    e.canceled = true
    count++
    e.npc.say "$count"
}

if (binding.hasVariable('count')) {
    count = binding.getVariable('count')
} else {
    count = 0
}

static def cacheScript() {
    "Test:0"
}