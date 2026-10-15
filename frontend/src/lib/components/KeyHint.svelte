<script lang="ts">
    /** The key that does the same as the button this sits in, shown as a small keycap. */
    export let key: string;
</script>

<kbd class="key-hint">{key}</kbd>

<style>
    .key-hint {
        display: inline-block;
        min-width: 1.1em;
        margin-right: 0.45rem;
        padding: 0 0.3rem;
        border: 1px solid currentColor;
        border-radius: 3px;
        font-family: monospace;
        font-size: 0.75em;
        line-height: 1.4;
        text-align: center;
        text-shadow: none;
        opacity: 0.85;
    }
</style>
