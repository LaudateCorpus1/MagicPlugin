package com.elmakers.mine.bukkit.spells;

import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Arrow;

import com.elmakers.mine.bukkit.magic.Spell;
import com.elmakers.mine.bukkit.persistence.dao.ParameterMap;

public class ArrowSpell extends Spell
{
    @Override
    public String getDescription()
    {
        return "Throws a magic arrow";
    }

    @Override
    public String getName()
    {
        return "arrow";
    }

    @Override
    public boolean onCast(ParameterMap parameters)
    {
        CraftPlayer cp = (CraftPlayer) player;
        Arrow arrow = cp.shootArrow();
        if (arrow == null)
        {
            castMessage(player, "Your arrow fizzled");
        }
        else
        {
            castMessage(player, "You fire a magical arrow");
        }
        return arrow != null;
    }
}
